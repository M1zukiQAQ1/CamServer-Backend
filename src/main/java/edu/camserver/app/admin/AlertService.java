package edu.camserver.app.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.camserver.app.service.HealthService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Emails the configured addresses when a camera, the seeing monitor, the server checks or the
 * health checks themselves are DOWN (optionally WARN) for longer than the configured delay, and
 * again when they recover. Reads the newest health report once a minute on its own thread.
 *
 * <p>This runs inside the backend, so it cannot report the backend or armageddon itself being
 * down; that needs a watchdog on another machine. State (what was already reported) survives
 * restarts in {@code <app.admin.data-dir>/alert-state.json}.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm z", Locale.US);

    public record Component(String key, String name, HealthService.Status status, List<String> reasons) {
    }

    public record Tracked(String name, HealthService.Status status, Instant since,
                          HealthService.Status alertedStatus, Instant alertedAt) {
    }

    public enum Kind { PROBLEM, REMINDER, RECOVERED }

    public record Change(String key, String name, Kind kind, HealthService.Status status, Instant since, List<String> reasons) {
    }

    public record LogEntry(Instant at, String subject, List<String> recipients, boolean ok, String error) {
    }

    private record StoredState(Map<String, Tracked> components, List<LogEntry> log) {
    }

    private final HealthService health;
    private final Mailer mailer;
    private final ObjectMapper json;
    private final Path settingsFile;
    private final Path stateFile;
    private final String siteUrl;
    private final ZoneId zone;
    private final Clock clock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "health-alerts");
        thread.setDaemon(true);
        return thread;
    });

    private AlertSettings settings = AlertSettings.defaults();
    private Map<String, Tracked> state = new LinkedHashMap<>();
    private final Deque<LogEntry> history = new ArrayDeque<>();
    private Instant nextAttempt = Instant.EPOCH;

    @Autowired
    public AlertService(HealthService health, Mailer mailer, AdminProperties admin,
                        @Value("${app.site.public-url:http://armageddon.deepspace.ucsb.edu}") String siteUrl,
                        @Value("${app.analytics.zone:America/Los_Angeles}") String zone) {
        this(health, mailer, Path.of(admin.getDataDir()), siteUrl, ZoneId.of(zone), Clock.systemUTC());
    }

    AlertService(HealthService health, Mailer mailer, Path dataDir, String siteUrl, ZoneId zone, Clock clock) {
        this.health = health;
        this.mailer = mailer;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.settingsFile = dataDir.resolve("alerts.json");
        this.stateFile = dataDir.resolve("alert-state.json");
        this.siteUrl = siteUrl.replaceAll("/+$", "");
        this.zone = zone;
        this.clock = clock;
        load();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        worker.scheduleWithFixedDelay(() -> {
            try {
                check();
            } catch (Throwable e) {
                log.error("Alert check failed", e);
            }
        }, 90, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        worker.shutdownNow();
    }

    public synchronized AlertSettings settings() {
        return settings;
    }

    public synchronized void updateSettings(AlertSettings next) throws IOException {
        AlertSettings valid = next.keepingPassword(settings).validated();
        writePrivate(settingsFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(valid));
        settings = valid;
        nextAttempt = Instant.EPOCH;
        log.info("Alert settings changed: enabled={}, {} recipient(s), smtp {}:{}", valid.enabled(),
                valid.recipients().size(), valid.smtpHost(), valid.smtpPort());
    }

    /** Current components with what has been reported, plus the mail log, for the panel. */
    public synchronized Map<String, Object> overview() {
        List<Map<String, Object>> components = new ArrayList<>();
        state.forEach((key, tracked) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("name", tracked.name());
            item.put("status", tracked.status());
            item.put("since", tracked.since());
            item.put("alertedStatus", tracked.alertedStatus());
            item.put("alertedAt", tracked.alertedAt());
            item.put("muted", settings.muted().contains(key));
            components.add(item);
        });
        List<LogEntry> entries = new ArrayList<>(history);
        java.util.Collections.reverse(entries);
        return Map.of("settings", settings.redacted(), "components", components, "log", entries);
    }

    /** Sends a test message with the given (unsaved) or stored settings; returns the error text or null. */
    public String sendTest(AlertSettings candidate) {
        AlertSettings effective;
        synchronized (this) {
            effective = candidate == null ? settings : candidate.keepingPassword(settings);
        }
        try {
            AlertSettings valid = new AlertSettings(false, effective.recipients(), effective.smtpHost(), effective.smtpPort(),
                    effective.security(), effective.username(), effective.password(), effective.from(),
                    effective.downAfterMinutes(), effective.alertOnWarn(), effective.repeatHours(), effective.muted()).validated();
            String subject = "[All Sky Cam] Test alert";
            mailer.send(valid, valid.recipients(), subject, "This is a test message from the All Sky Cam admin panel ("
                    + siteUrl + "/admin).\nAlerts for DOWN components will look like this one.\n");
            record(new LogEntry(clock.instant(), subject, valid.recipients(), true, null));
            return null;
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            record(new LogEntry(clock.instant(), "[All Sky Cam] Test alert", effective.recipients(), false, error));
            return error;
        }
    }

    synchronized void check() {
        Optional<HealthService.HealthReport> report = health.report();
        if (report.isEmpty()) return;
        Instant now = clock.instant();
        List<Component> components = components(report.get(), now);
        Map<String, Tracked> next = new LinkedHashMap<>(state);
        List<Change> changes = decide(next, components, settings, now);
        if (changes.isEmpty()) {
            state = next;
            persistState();
            return;
        }
        if (now.isBefore(nextAttempt)) {
            keepStatusesOnly(next);
            return;
        }
        String subject = subject(changes);
        try {
            mailer.send(settings, settings.recipients(), subject, body(changes, report.get()));
            state = next;
            record(new LogEntry(now, subject, settings.recipients(), true, null));
            log.info("Alert sent to {}: {}", settings.recipients(), subject);
        } catch (Exception e) {
            keepStatusesOnly(next);
            nextAttempt = now.plus(RETRY_AFTER_FAILURE);
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            record(new LogEntry(now, subject, settings.recipients(), false, error));
            log.warn("Alert mail failed ({}); retrying in {} min", error, RETRY_AFTER_FAILURE.toMinutes());
        }
        persistState();
    }

    /**
     * Updates {@code state} with the current statuses and returns the mail-worthy changes. Pure
     * apart from mutating {@code state}, so the rules can be tested without mail or health checks.
     */
    static List<Change> decide(Map<String, Tracked> state, List<Component> components, AlertSettings settings, Instant now) {
        List<Change> changes = new ArrayList<>();
        Duration delay = Duration.ofMinutes(settings.downAfterMinutes());
        List<String> present = new ArrayList<>();
        for (Component component : components) {
            present.add(component.key());
            Tracked previous = state.get(component.key());
            Instant since = previous == null || previous.status() != component.status() ? now : previous.since();
            HealthService.Status alerted = previous == null ? null : previous.alertedStatus();
            Instant alertedAt = previous == null ? null : previous.alertedAt();
            boolean problem = component.status() == HealthService.Status.DOWN
                    || settings.alertOnWarn() && component.status() == HealthService.Status.WARN;
            if (settings.muted().contains(component.key()) || !settings.enabled()) {
                alerted = null;
                alertedAt = null;
            } else if (problem && !since.plus(delay).isAfter(now)) {
                if (alerted != component.status()) {
                    changes.add(new Change(component.key(), component.name(), Kind.PROBLEM, component.status(), since, component.reasons()));
                    alerted = component.status();
                    alertedAt = now;
                } else if (settings.repeatHours() > 0 && !alertedAt.plus(Duration.ofHours(settings.repeatHours())).isAfter(now)) {
                    changes.add(new Change(component.key(), component.name(), Kind.REMINDER, component.status(), since, component.reasons()));
                    alertedAt = now;
                }
            } else if (!problem && alerted != null) {
                changes.add(new Change(component.key(), component.name(), Kind.RECOVERED, component.status(), since, component.reasons()));
                alerted = null;
                alertedAt = null;
            }
            state.put(component.key(), new Tracked(component.name(), component.status(), since, alerted, alertedAt));
        }
        state.keySet().removeIf(key -> !present.contains(key));
        return changes;
    }

    static List<Component> components(HealthService.HealthReport report, Instant now) {
        List<Component> components = new ArrayList<>();
        if (report.cameras() != null) {
            for (HealthService.CameraHealth camera : report.cameras()) {
                String name = camera.siteName() == null || camera.siteName().isBlank() ? camera.cameraId() : camera.siteName();
                components.add(new Component("camera:" + camera.cameraId(), name, camera.status(), camera.reasons()));
            }
        }
        if (report.seeing() != null) {
            components.add(new Component("seeing", report.seeing().label(), report.seeing().status(), report.seeing().reasons()));
        }
        if (report.server() != null) {
            components.add(new Component("server", "Server (armageddon)", report.server().status(), report.server().reasons()));
        }
        long age = Duration.between(report.generatedAt(), now).toMinutes();
        components.add(new Component("health-checks", "Health checks", report.stale() ? HealthService.Status.DOWN : HealthService.Status.OK,
                report.stale() ? List.of("The background health checks last finished " + age + " minutes ago.") : List.of()));
        return components;
    }

    private String subject(List<Change> changes) {
        if (changes.size() == 1) {
            Change change = changes.get(0);
            return "[All Sky Cam] " + switch (change.kind()) {
                case PROBLEM -> change.status() + ": " + change.name();
                case REMINDER -> "Still " + change.status() + ": " + change.name();
                case RECOVERED -> "Recovered: " + change.name();
            };
        }
        long problems = changes.stream().filter(c -> c.kind() != Kind.RECOVERED).count();
        return "[All Sky Cam] " + problems + " problem(s), " + (changes.size() - problems) + " recovered";
    }

    private String body(List<Change> changes, HealthService.HealthReport report) {
        StringBuilder text = new StringBuilder();
        for (Change change : changes) {
            text.append(switch (change.kind()) {
                case PROBLEM -> change.name() + " is " + change.status();
                case REMINDER -> change.name() + " is still " + change.status();
                case RECOVERED -> change.name() + " recovered (now " + change.status() + ")";
            });
            text.append(" since ").append(TIME.format(change.since().atZone(zone))).append(".\n");
            for (String reason : change.reasons()) text.append("  - ").append(reason).append('\n');
            text.append('\n');
        }
        text.append("Overall status: ").append(report.status()).append('\n');
        text.append("Health panel: ").append(siteUrl).append("/health\n");
        text.append("Alert settings: ").append(siteUrl).append("/admin (UCSB VPN)\n\n");
        text.append("These alerts come from the backend on armageddon, so they cannot report armageddon itself being down.\n");
        return text.toString();
    }

    private void keepStatusesOnly(Map<String, Tracked> next) {
        Map<String, Tracked> merged = new LinkedHashMap<>();
        next.forEach((key, tracked) -> {
            Tracked old = state.get(key);
            merged.put(key, new Tracked(tracked.name(), tracked.status(), tracked.since(),
                    old == null ? null : old.alertedStatus(), old == null ? null : old.alertedAt()));
        });
        state = merged;
        persistState();
    }

    private synchronized void record(LogEntry entry) {
        history.addLast(entry);
        while (history.size() > 50) history.pollFirst();
        persistState();
    }

    private void load() {
        try {
            if (Files.exists(settingsFile)) settings = json.readValue(settingsFile.toFile(), AlertSettings.class).validated();
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot read alert settings {}: {}; alerts stay off", settingsFile, e.toString());
            settings = AlertSettings.defaults();
        }
        try {
            if (Files.exists(stateFile)) {
                StoredState stored = json.readValue(stateFile.toFile(), new TypeReference<>() {});
                if (stored.components() != null) state = new LinkedHashMap<>(stored.components());
                if (stored.log() != null) history.addAll(stored.log());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot read alert state {}: {}", stateFile, e.toString());
        }
    }

    private void persistState() {
        try {
            writePrivate(stateFile, json.writeValueAsBytes(new StoredState(state, new ArrayList<>(history))));
        } catch (IOException e) {
            log.warn("Cannot save alert state {}: {}", stateFile, e.toString());
        }
    }

    /** Atomic write of a file only its owner (root) can read: it may hold the SMTP password. */
    private static void writePrivate(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX file system.
            }
            Files.write(temporary, content);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
