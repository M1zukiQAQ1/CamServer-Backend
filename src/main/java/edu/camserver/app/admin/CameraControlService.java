package edu.camserver.app.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Remote control of the camera hosts (the Raspberry Pis). Each Pi runs an agent that posts a
 * heartbeat ({@code POST /api/devices/heartbeat}) every few seconds with its sensors and host
 * information; the reply carries the commands an admin queued for it. The Pis only ever call
 * out, the same way they upload frames, so nothing has to reach into the camera networks.
 *
 * <p>Commands are delivered at most once and expire when the Pi does not pick them up in time,
 * so a reboot queued while a Pi is offline never fires hours later when it comes back.
 *
 * <p>Devices and their tokens are listed in {@code <app.admin.data-dir>/devices.json} (only the
 * SHA-256 of each token is stored; {@code scripts/device_token.py} writes entries). The file is
 * re-read when it changes. Heartbeats and command history live in memory only.
 */
@Service
public class CameraControlService {

    private static final Logger log = LoggerFactory.getLogger(CameraControlService.class);

    /** A Pi that has not reported for this long is shown offline. */
    static final Duration OFFLINE_AFTER = Duration.ofSeconds(30);
    /** A command not picked up by then is dropped. */
    static final Duration COMMAND_TTL = Duration.ofSeconds(90);
    /** A delivered command without a result by then is marked as unanswered. */
    static final Duration RESULT_TIMEOUT = Duration.ofMinutes(5);
    /** Sensor history: one sample per {@link #SAMPLE_EVERY}, kept for {@link #HISTORY}. */
    static final Duration SAMPLE_EVERY = Duration.ofSeconds(30);
    static final Duration HISTORY = Duration.ofHours(24);
    private static final int COMMANDS_KEPT = 30;
    private static final int MAX_REPORT_CHARS = 64 * 1024;

    public enum CommandType {
        /** Take a picture now instead of waiting for the next scheduled one. */
        CAPTURE,
        /** Restart the capture/stream program (systemd service). */
        RESTART_SERVICE,
        /** Reboot the Pi. */
        REBOOT,
        /** Power the Pi off. It stays off until someone power-cycles it on site. */
        SHUTDOWN,
        /** Set the enclosure heater: {@code mode} AUTO/ON/OFF, optional {@code target} °C for AUTO. */
        HEATER;

        boolean needsConfirmation() {
            return this == REBOOT || this == SHUTDOWN;
        }
    }

    public enum CommandStatus { PENDING, DELIVERED, DONE, FAILED, EXPIRED, UNANSWERED, SUPERSEDED }

    static final List<String> HEATER_MODES = List.of("AUTO", "ON", "OFF");
    static final double MIN_HEATER_TARGET = -30;
    static final double MAX_HEATER_TARGET = 60;

    public static final class Command {
        public final String id;
        public final CommandType type;
        public final Instant issuedAt;
        public final String issuedBy;
        /** Command arguments sent along to the Pi (HEATER: mode, target); empty for the others. */
        public final Map<String, Object> params;
        public CommandStatus status = CommandStatus.PENDING;
        public Instant deliveredAt;
        public Instant completedAt;
        public String message;
        public Map<String, Object> result;

        Command(String id, CommandType type, Instant issuedAt, String issuedBy, Map<String, Object> params) {
            this.id = id;
            this.type = type;
            this.issuedAt = issuedAt;
            this.issuedBy = issuedBy;
            this.params = params;
        }

        Map<String, Object> toWire() {
            Map<String, Object> wire = new LinkedHashMap<>(params);
            wire.put("id", id);
            wire.put("type", type.name());
            return wire;
        }

        Map<String, Object> toView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", id);
            view.put("type", type.name());
            view.put("status", status.name());
            view.put("issuedAt", issuedAt);
            view.put("issuedBy", issuedBy);
            view.put("params", params.isEmpty() ? null : params);
            view.put("deliveredAt", deliveredAt);
            view.put("completedAt", completedAt);
            view.put("message", message);
            view.put("result", result);
            return view;
        }
    }

    public record Sample(Instant at, Double temperature, Double humidity, Double cpuTemperature) {
    }

    public record DeviceEntry(String id, String name, String tokenSha256) {
    }

    private record DevicesFile(List<DeviceEntry> devices) {
    }

    private static final class Device {
        final String id;
        String name;
        Instant lastSeen;
        String sourceAddress;
        Map<String, Object> report = Map.of();
        final Deque<Sample> samples = new ArrayDeque<>();
        final Deque<Command> commands = new ArrayDeque<>();

        Device(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class CommandRejected extends RuntimeException {
        public CommandRejected(String message) {
            super(message);
        }
    }

    private final Path devicesFile;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private Map<String, DeviceEntry> byTokenHash = Map.of();
    private FileTime loadedModified;

    @Autowired
    public CameraControlService(AdminProperties admin) {
        this(Path.of(admin.getDataDir()).resolve("devices.json"), Clock.systemUTC());
    }

    CameraControlService(Path devicesFile, Clock clock) {
        this.devicesFile = devicesFile;
        this.clock = clock;
        reloadIfChanged();
    }

    /** The registered device a heartbeat token belongs to. */
    public synchronized Optional<String> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        reloadIfChanged();
        DeviceEntry entry = byTokenHash.get(sha256(token.trim()));
        return Optional.ofNullable(entry).map(DeviceEntry::id);
    }

    /**
     * Stores a device's report and returns the commands it should run now.
     *
     * @param report the agent's JSON body; {@code results} (a list of {@code {id, ok, message, ...}})
     *               completes earlier commands, everything else is shown as-is in the panel
     */
    public synchronized List<Map<String, Object>> heartbeat(String deviceId, Map<String, Object> report, String sourceAddress) {
        Instant now = clock.instant();
        Device device = device(deviceId);
        device.lastSeen = now;
        device.sourceAddress = sourceAddress;
        Map<String, Object> stored = new LinkedHashMap<>(report == null ? Map.of() : report);
        Object results = stored.remove("results");
        if (results instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) complete(device, map, now);
            }
        }
        if (json.valueToTree(stored).toString().length() <= MAX_REPORT_CHARS) {
            device.report = stored;
        } else {
            log.warn("Ignored an oversized report from {}", deviceId);
        }
        sample(device, stored, now);

        expire(device, now);
        List<Map<String, Object>> deliver = new ArrayList<>();
        for (Command command : device.commands) {
            if (command.status == CommandStatus.PENDING) {
                command.status = CommandStatus.DELIVERED;
                command.deliveredAt = now;
                deliver.add(command.toWire());
                log.info("Delivered {} {} to {}", command.type, command.id, deviceId);
            }
        }
        return deliver;
    }

    public synchronized Command queue(String deviceId, CommandType type, String confirm, String issuedBy) {
        return queue(deviceId, type, confirm, issuedBy, Map.of());
    }

    /**
     * Queues a command. HEATER needs {@code mode} (AUTO/ON/OFF) and takes an optional {@code target}
     * in °C; a newer HEATER replaces one the Pi has not picked up yet (the latest setting wins).
     */
    public synchronized Command queue(String deviceId, CommandType type, String confirm, String issuedBy,
                                      Map<String, Object> params) {
        Map<String, Object> args = type == CommandType.HEATER ? heaterParams(params) : Map.of();
        reloadIfChanged();
        Device device = devices.get(deviceId);
        if (device == null) throw new CommandRejected("Unknown camera host " + deviceId + ".");
        Instant now = clock.instant();
        expire(device, now);
        if (type.needsConfirmation() && !deviceId.equals(confirm)) {
            throw new CommandRejected("Type the host name (" + deviceId + ") to confirm.");
        }
        if (!capabilities(device).contains(type.name())) {
            throw new CommandRejected(device.lastSeen == null
                    ? deviceId + " has not reported yet."
                    : deviceId + " does not support " + type.name() + ".");
        }
        for (Command earlier : device.commands) {
            boolean open = earlier.status == CommandStatus.PENDING || earlier.status == CommandStatus.DELIVERED;
            if (open && type == CommandType.HEATER && earlier.type == CommandType.HEATER) {
                if (earlier.status == CommandStatus.PENDING) {
                    earlier.status = CommandStatus.SUPERSEDED;
                    earlier.completedAt = now;
                    earlier.message = "Replaced by a newer heater setting before the host picked it up.";
                }
                continue;  // a delivered one runs first, the new one after it
            }
            if (open && (earlier.type == type || earlier.type.needsConfirmation() || type.needsConfirmation())) {
                throw new CommandRejected("A " + earlier.type.name() + " command is still "
                        + earlier.status.name().toLowerCase() + ".");
            }
        }
        Command command = new Command(UUID.randomUUID().toString(), type, now, issuedBy, args);
        if (!online(device, now)) {
            command.message = "Host is offline; the command expires unless it reports within "
                    + COMMAND_TTL.toSeconds() + " s.";
        }
        device.commands.addLast(command);
        while (device.commands.size() > COMMANDS_KEPT) device.commands.removeFirst();
        return command;
    }

    /** Everything the admin panel shows, one entry per registered or reporting device. */
    public synchronized List<Map<String, Object>> overview() {
        reloadIfChanged();
        Instant now = clock.instant();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Device device : devices.values()) {
            expire(device, now);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", device.id);
            view.put("name", device.name);
            view.put("online", online(device, now));
            view.put("lastSeen", device.lastSeen);
            view.put("sourceAddress", device.sourceAddress);
            view.put("capabilities", capabilities(device));
            view.put("report", device.report);
            view.put("history", List.copyOf(device.samples));
            List<Map<String, Object>> commands = new ArrayList<>();
            device.commands.descendingIterator().forEachRemaining(command -> commands.add(command.toView()));
            view.put("commands", commands);
            list.add(view);
        }
        return list;
    }

    private static Map<String, Object> heaterParams(Map<String, Object> params) {
        Object mode = params == null ? null : params.get("mode");
        if (!(mode instanceof String m) || !HEATER_MODES.contains(m.toUpperCase())) {
            throw new CommandRejected("Heater mode must be one of " + String.join(", ", HEATER_MODES) + ".");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("mode", m.toUpperCase());
        Object target = params.get("target");
        if (target != null) {
            Double value = number(target);
            if (value == null || value < MIN_HEATER_TARGET || value > MAX_HEATER_TARGET) {
                throw new CommandRejected("Heater target must be a temperature between "
                        + (int) MIN_HEATER_TARGET + " and " + (int) MAX_HEATER_TARGET + " °C.");
            }
            args.put("target", Math.round(value * 10) / 10.0);
        }
        return args;
    }

    private void complete(Device device, Map<?, ?> result, Instant now) {
        Object id = result.get("id");
        for (Command command : device.commands) {
            if (!command.id.equals(id)) continue;
            Map<String, Object> fields = new LinkedHashMap<>();
            result.forEach((key, value) -> fields.put(String.valueOf(key), value));
            fields.remove("id");
            Object message = fields.remove("message");
            boolean ok = Boolean.TRUE.equals(fields.remove("ok"));
            boolean finished = !Boolean.TRUE.equals(fields.remove("running"));
            command.message = message == null ? null : String.valueOf(message);
            command.result = fields.isEmpty() ? null : fields;
            if (finished) {
                command.status = ok ? CommandStatus.DONE : CommandStatus.FAILED;
                command.completedAt = now;
            }
            log.info("{} {} on {}: {} {}", command.type, command.id, device.id, command.status, command.message);
            return;
        }
    }

    private static void expire(Device device, Instant now) {
        for (Command command : device.commands) {
            if (command.status == CommandStatus.PENDING && now.isAfter(command.issuedAt.plus(COMMAND_TTL))) {
                command.status = CommandStatus.EXPIRED;
                command.completedAt = now;
                command.message = "The host did not pick it up within " + COMMAND_TTL.toSeconds() + " s; not run.";
            } else if (command.status == CommandStatus.DELIVERED && now.isAfter(command.deliveredAt.plus(RESULT_TIMEOUT))) {
                command.status = CommandStatus.UNANSWERED;
                command.completedAt = now;
                if (command.message == null) command.message = "Delivered, but the host never reported a result.";
            }
        }
    }

    private void sample(Device device, Map<String, Object> report, Instant now) {
        Sample last = device.samples.peekLast();
        if (last != null && last.at().plus(SAMPLE_EVERY).isAfter(now)) return;
        Map<?, ?> sensors = report.get("sensors") instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> host = report.get("host") instanceof Map<?, ?> map ? map : Map.of();
        Sample sample = new Sample(now, number(sensors.get("temperature")), number(sensors.get("humidity")),
                number(host.get("cpuTemperature")));
        if (sample.temperature() == null && sample.humidity() == null && sample.cpuTemperature() == null) return;
        device.samples.addLast(sample);
        while (!device.samples.isEmpty() && device.samples.peekFirst().at().isBefore(now.minus(HISTORY))) {
            device.samples.removeFirst();
        }
    }

    private static boolean online(Device device, Instant now) {
        return device.lastSeen != null && device.lastSeen.plus(OFFLINE_AFTER).isAfter(now);
    }

    private static List<String> capabilities(Device device) {
        if (device.report.get("capabilities") instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private Device device(String id) {
        return devices.computeIfAbsent(id, key -> new Device(key, key));
    }

    private void reloadIfChanged() {
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(devicesFile);
        } catch (NoSuchFileException e) {
            if (loadedModified != null || byTokenHash.isEmpty()) {
                if (loadedModified != null) log.warn("{} disappeared; no camera host can report", devicesFile);
                byTokenHash = Map.of();
                loadedModified = null;
            }
            return;
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", devicesFile, e.getMessage());
            return;
        }
        if (modified.equals(loadedModified)) return;
        try {
            DevicesFile file = json.readValue(devicesFile.toFile(), DevicesFile.class);
            Map<String, DeviceEntry> tokens = new LinkedHashMap<>();
            for (DeviceEntry entry : file.devices() == null ? List.<DeviceEntry>of() : file.devices()) {
                if (entry.id() == null || entry.tokenSha256() == null) continue;
                tokens.put(entry.tokenSha256().toLowerCase(), entry);
                device(entry.id()).name = entry.name() == null || entry.name().isBlank() ? entry.id() : entry.name();
            }
            byTokenHash = tokens;
            loadedModified = modified;
            log.info("Loaded {} camera host(s) from {}", tokens.size(), devicesFile);
        } catch (IOException e) {
            log.warn("Cannot parse {}: {}", devicesFile, e.getMessage());
        }
    }

    private static Double number(Object value) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    static String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
