package edu.camserver.app.service;

import edu.camserver.app.config.HealthProperties;
import edu.camserver.app.model.Camera;
import edu.camserver.app.model.Image;
import edu.camserver.app.repository.CameraRepository;
import edu.camserver.app.repository.ImageRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Builds the health report behind {@code GET /api/health}: for every camera in the database (and
 * every one named in {@code app.health.cameras}) how fresh its uploads are and whether its host
 * answers; whether the seeing-monitor producer is streaming; and the state of this server.
 *
 * <p>The checks run on a background thread every {@code app.health.refresh-seconds}, starting
 * when the application is ready. Requests only read the newest result, so opening the page never
 * probes a host or queries the database, however many people have it open.
 *
 * <p>Status rules (see {@link #evaluateCamera} and {@link #evaluateSeeing}, which are pure so
 * they can be unit-tested): OK, WARN when uploads are late or the host is silent, DOWN when
 * uploads stopped, INACTIVE for a camera nobody expects frames from (no host configured and no
 * frame for {@code inactive-after-days}). INACTIVE cameras do not affect the overall status.
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    /** Shortest allowed interval: one check can take several seconds when a host is silent. */
    private static final int MIN_REFRESH_SECONDS = 10;

    /** How long a request made right after startup waits for the first check to finish. */
    private static final int FIRST_REPORT_WAIT_SECONDS = 15;

    public enum Status {
        OK, WARN, DOWN, INACTIVE, UNKNOWN;

        /** The more serious of two statuses; INACTIVE and UNKNOWN never outrank a real problem. */
        static Status worst(Status a, Status b) {
            return severity(b) > severity(a) ? b : a;
        }

        private static int severity(Status status) {
            return switch (status) {
                case DOWN -> 3;
                case WARN -> 2;
                case UNKNOWN -> 1;
                case OK, INACTIVE -> 0;
            };
        }
    }

    public record HostProbe(String host, String address, boolean reachable, Boolean icmp,
                            Map<String, Boolean> tcp, Long latencyMs, String error) {
        static HostProbe failed(String host, String error) {
            return new HostProbe(host, null, false, null, Map.of(), null, error);
        }
    }

    public record LatestFrame(long imgId, String fileName, Instant timestamp, String timeZone, int exposure, int gain,
                              float temperature, float humidity, Boolean isDayTime, Long jpgBytes) {
    }

    public record CameraHealth(String cameraId, String siteName, String timeZone, Status status, List<String> reasons,
                               String note, boolean monitored, LatestFrame latestFrame, Long lastFrameAgeSeconds,
                               Long framesLast24h, Long framesLastHour, HostProbe host) {
    }

    public record SeeingHealth(String label, Status status, List<String> reasons, String note, boolean live,
                               String state, String producer, String remoteAddress, Instant startedAt,
                               Long lastFragmentAgeMs, Double fps, Long averageKbps, Integer width, Integer height,
                               Integer viewers, Double telemetryAgeSeconds, String position, Map<String, Object> telemetry,
                               Map<String, Object> settings, Instant lastSessionEndedAt, String lastSessionEndReason,
                               HostProbe host) {
    }

    public record ServerHealth(Status status, List<String> reasons, String hostname, Instant startedAt,
                               long uptimeSeconds, boolean databaseOk, String databaseError, String imagesDir,
                               Long diskTotalBytes, Long diskFreeBytes, Map<String, Object> archive, Integer liveViewers) {
    }

    /**
     * {@code cacheSeconds} repeats {@code refreshSeconds} for pages built before the checks moved to
     * the background. {@code stale} is set when the background checks have not produced a report
     * for three intervals; the overall status is then at least UNKNOWN.
     */
    public record HealthReport(Instant generatedAt, int cacheSeconds, int refreshSeconds, boolean stale, Status status,
                               Map<String, Long> summary, List<CameraHealth> cameras, SeeingHealth seeing,
                               ServerHealth server) {
    }

    /** Status plus the human-readable reasons behind it. */
    public record Evaluation(Status status, List<String> reasons) {
    }

    private final HealthProperties properties;
    private final CameraRepository cameraRepository;
    private final ImageRepository imageRepository;
    private final LiveStreamService live;
    private final FrameService frameService;
    private final SettingsService settingsService;
    private final ArchiveAutoCompressor autoCompressor;
    private final Path imagesDir;
    private final int refreshSeconds;
    private final ExecutorService probes;
    private final ScheduledExecutorService refresher;
    private final CompletableFuture<HealthReport> firstReport = new CompletableFuture<>();

    private volatile HealthReport latest;

    public HealthService(HealthProperties properties,
                         CameraRepository cameraRepository,
                         ImageRepository imageRepository,
                         LiveStreamService live,
                         FrameService frameService,
                         SettingsService settingsService,
                         ArchiveAutoCompressor autoCompressor,
                         @Value("${app.images.base-dir}") String imagesDir) {
        this.properties = properties;
        this.cameraRepository = cameraRepository;
        this.imageRepository = imageRepository;
        this.live = live;
        this.frameService = frameService;
        this.settingsService = settingsService;
        this.autoCompressor = autoCompressor;
        this.imagesDir = Path.of(imagesDir);
        this.refreshSeconds = Math.max(MIN_REFRESH_SECONDS, properties.getRefreshSeconds());
        this.probes = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "health-probe");
            thread.setDaemon(true);
            return thread;
        });
        this.refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "health-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the background checks. They get their own thread rather than Spring's shared
     * scheduler, so a slow check cannot delay other scheduled jobs such as the archive run.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startBackgroundChecks() {
        log.info("Health checks run in the background every {} s", refreshSeconds);
        refresher.scheduleWithFixedDelay(this::refresh, 0, refreshSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        refresher.shutdownNow();
        probes.shutdownNow();
    }

    private void refresh() {
        try {
            HealthReport report = build(Instant.now());
            latest = report;
            firstReport.complete(report);
        } catch (Throwable e) {
            // Anything escaping here would silently cancel every later run.
            log.error("Health check failed; the previous report stays in place", e);
        }
    }

    /**
     * The newest report of the background checks. Empty only when the first check after startup
     * has not finished within {@link #FIRST_REPORT_WAIT_SECONDS}.
     */
    public Optional<HealthReport> report() {
        HealthReport report = latest;
        if (report == null) {
            try {
                report = firstReport.get(FIRST_REPORT_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.of(markIfStale(report, Instant.now()));
    }

    /**
     * A report older than three check intervals means the background checks stopped or hang:
     * flag it and raise the overall status to at least UNKNOWN rather than show old results as current.
     */
    static HealthReport markIfStale(HealthReport report, Instant now) {
        Duration staleAfter = Duration.ofSeconds(report.refreshSeconds() * 3L);
        if (report.stale() || !report.generatedAt().plus(staleAfter).isBefore(now)) {
            return report;
        }
        return new HealthReport(report.generatedAt(), report.cacheSeconds(), report.refreshSeconds(), true,
                Status.worst(report.status(), Status.UNKNOWN), report.summary(), report.cameras(), report.seeing(),
                report.server());
    }

    private HealthReport build(Instant now) {
        Map<String, HealthProperties.CameraEntry> configured = new LinkedHashMap<>();
        for (HealthProperties.CameraEntry entry : properties.getCameras()) {
            if (entry.getCameraId() != null && !entry.getCameraId().isBlank()) {
                configured.put(entry.getCameraId().trim(), entry);
            }
        }

        // Probe every host while the database queries run.
        Map<String, CompletableFuture<HostProbe>> hostProbes = new LinkedHashMap<>();
        configured.forEach((cameraId, entry) -> {
            if (hasText(entry.getHost())) {
                hostProbes.put(cameraId, probeAsync(entry.getHost(), entry.getPorts()));
            }
        });
        HealthProperties.Seeing seeingConfig = properties.getSeeing();
        CompletableFuture<HostProbe> seeingProbe = hasText(seeingConfig.getHost())
                ? probeAsync(seeingConfig.getHost(), seeingConfig.getPorts())
                : null;

        List<CameraHealth> cameras = new ArrayList<>();
        String databaseError = null;
        try {
            Map<String, Camera> known = new LinkedHashMap<>();
            for (Camera camera : cameraRepository.findAll()) {
                if (camera.getCameraId() != null) {
                    known.put(camera.getCameraId(), camera);
                }
            }
            // Configured cameras the database has never heard of still get a card.
            configured.keySet().forEach(cameraId -> known.putIfAbsent(cameraId, null));
            for (Map.Entry<String, Camera> entry : known.entrySet()) {
                cameras.add(cameraHealth(now, entry.getKey(), entry.getValue(), configured.get(entry.getKey()),
                        await(hostProbes.get(entry.getKey()))));
            }
        } catch (RuntimeException e) {
            log.warn("Health report: database query failed", e);
            databaseError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        SeeingHealth seeing = seeingHealth(now, seeingConfig, await(seeingProbe));
        ServerHealth server = serverHealth(now, databaseError);

        Map<String, Long> summary = new LinkedHashMap<>();
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        Status overall = server.status();
        for (CameraHealth camera : cameras) {
            counts.merge(camera.status(), 1L, Long::sum);
            overall = Status.worst(overall, camera.status());
        }
        counts.merge(seeing.status(), 1L, Long::sum);
        overall = Status.worst(overall, seeing.status());
        for (Status status : Status.values()) {
            summary.put(status.name().toLowerCase(Locale.ROOT), counts.getOrDefault(status, 0L));
        }
        return new HealthReport(now, refreshSeconds, refreshSeconds, false, overall, summary, cameras, seeing, server);
    }

    private CameraHealth cameraHealth(Instant now, String cameraId, Camera camera,
                                      HealthProperties.CameraEntry config, HostProbe probe) {
        Optional<Image> newest = imageRepository.findNewestByCamera(cameraId, PageRequest.of(0, 1)).stream().findFirst();
        Long last24h = imageRepository.countByCameraSince(cameraId, utc(now.minus(Duration.ofHours(24))));
        Long lastHour = imageRepository.countByCameraSince(cameraId, utc(now.minus(Duration.ofHours(1))));

        LatestFrame latest = newest.map(this::latestFrame).orElse(null);
        Instant lastAt = latest == null ? null : latest.timestamp();
        boolean monitored = config != null && hasText(config.getHost());
        Duration warnAfter = Duration.ofMinutes(config != null && config.getWarnAfterMinutes() != null
                ? config.getWarnAfterMinutes() : properties.getWarnAfterMinutes());
        Duration downAfter = Duration.ofMinutes(config != null && config.getDownAfterMinutes() != null
                ? config.getDownAfterMinutes() : properties.getDownAfterMinutes());
        Evaluation evaluation = evaluateCamera(now, lastAt, monitored, probe == null ? null : probe.reachable(),
                latest == null ? null : latest.jpgBytes(), warnAfter, downAfter,
                Duration.ofDays(properties.getInactiveAfterDays()));

        String siteName = camera != null && hasText(camera.getSiteName()) ? camera.getSiteName() : cameraId;
        String timeZone = camera != null ? camera.getTimeZone() : null;
        if (timeZone == null && latest != null) {
            timeZone = latest.timeZone();
        }
        return new CameraHealth(cameraId, siteName, timeZone, evaluation.status(), evaluation.reasons(),
                config == null ? null : config.getNote(), monitored, latest,
                lastAt == null ? null : Math.max(0, Duration.between(lastAt, now).getSeconds()),
                last24h, lastHour, probe);
    }

    private LatestFrame latestFrame(Image image) {
        String fileName = image.getImgPath() == null ? null : Path.of(image.getImgPath()).getFileName().toString();
        Long jpgBytes = null;
        if (image.getImgPath() != null) {
            Path jpg = Path.of(image.getImgPath() + ".jpg");
            try {
                if (Files.isRegularFile(jpg)) {
                    jpgBytes = Files.size(jpg);
                }
            } catch (IOException e) {
                log.debug("Health report: cannot stat {}", jpg, e);
            }
        }
        return new LatestFrame(image.getImgId(), fileName, image.getTimestamp(), image.getTimeZone(),
                image.getExposure(), image.getGain(), image.getTemperature(), image.getHumidity(),
                image.getIsDayTime(), jpgBytes);
    }

    /**
     * Status of one camera from what is known about it. {@code hostReachable} is null when no
     * host is configured, {@code jpgBytes} null when the newest frame has no JPEG on disk.
     */
    static Evaluation evaluateCamera(Instant now, Instant lastFrameAt, boolean monitored, Boolean hostReachable,
                                     Long jpgBytes, Duration warnAfter, Duration downAfter, Duration inactiveAfter) {
        List<String> reasons = new ArrayList<>();
        Status status;
        if (lastFrameAt == null) {
            status = monitored ? Status.DOWN : Status.INACTIVE;
            reasons.add("No frames from this camera in the database.");
        } else {
            Duration age = Duration.between(lastFrameAt, now);
            if (!monitored && age.compareTo(inactiveAfter) > 0) {
                status = Status.INACTIVE;
                reasons.add("No frames for " + describe(age) + " and no host is configured for it, so it is not expected to upload.");
            } else if (age.compareTo(downAfter) > 0) {
                status = Status.DOWN;
                reasons.add("Last frame " + describe(age) + " ago; uploads are considered stopped after " + describe(downAfter) + ".");
            } else if (age.compareTo(warnAfter) > 0) {
                status = Status.WARN;
                reasons.add("Last frame " + describe(age) + " ago, later than the expected " + describe(warnAfter) + ".");
            } else {
                status = Status.OK;
            }
        }
        if (Boolean.FALSE.equals(hostReachable)) {
            reasons.add("The host answers neither ping nor its TCP ports from this server.");
            status = Status.worst(status, Status.WARN);
        } else if (Boolean.TRUE.equals(hostReachable) && status == Status.DOWN) {
            reasons.add("The host answers, so the capture service on it is probably stopped or cannot upload.");
        }
        if (lastFrameAt != null && jpgBytes != null && jpgBytes == 0) {
            reasons.add("The newest JPEG on disk is empty (0 bytes).");
            status = Status.worst(status, Status.WARN);
        }
        return new Evaluation(status, List.copyOf(reasons));
    }

    private SeeingHealth seeingHealth(Instant now, HealthProperties.Seeing config, HostProbe probe) {
        Map<String, Object> status = live.status();
        Map<String, Object> telemetry = frameService.telemetry();
        boolean isLive = Boolean.TRUE.equals(status.get("live"));
        Long fragmentAge = asLong(status.get("lastFragmentAgeMs"));
        Double telemetryAge = asDouble(telemetry.get("ageSeconds"));
        String endReason = asString(status.get("lastSessionEndReason"));
        Evaluation evaluation = evaluateSeeing(isLive, fragmentAge, telemetryAge,
                probe == null ? null : probe.reachable(),
                Duration.ofSeconds(config.getStaleAfterSeconds()),
                Duration.ofSeconds(config.getTelemetryStaleAfterSeconds()), endReason);

        @SuppressWarnings("unchecked")
        Map<String, Object> extras = telemetry.get("extras") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> shownTelemetry = new LinkedHashMap<>(extras);
        shownTelemetry.put("latencyMs", telemetry.get("latencyMs"));

        return new SeeingHealth(config.getLabel(), evaluation.status(), evaluation.reasons(), config.getNote(),
                isLive, asString(status.get("state")), asString(status.get("producer")),
                asString(status.get("remoteAddress")), asInstant(status.get("startedAt")), fragmentAge,
                asDouble(status.get("fps")), asLong(status.get("averageKbps")), asInteger(status.get("width")),
                asInteger(status.get("height")), asInteger(status.get("viewers")), telemetryAge,
                asString(telemetry.get("pos")), shownTelemetry, settingsService.getSettings(),
                asInstant(status.get("lastSessionEndedAt")), endReason, probe);
    }

    /** Status of the seeing-monitor feed from the relay's view of the producer. */
    static Evaluation evaluateSeeing(boolean live, Long fragmentAgeMs, Double telemetryAgeSeconds, Boolean hostReachable,
                                     Duration staleAfter, Duration telemetryStaleAfter, String lastSessionEndReason) {
        List<String> reasons = new ArrayList<>();
        Status status = Status.OK;
        if (!live) {
            status = Status.DOWN;
            reasons.add(lastSessionEndReason == null
                    ? "No camera producer is connected."
                    : "No camera producer is connected; the last session ended: " + lastSessionEndReason + ".");
        } else if (fragmentAgeMs != null && fragmentAgeMs > staleAfter.toMillis()) {
            status = Status.WARN;
            reasons.add("The producer is connected but sent no video for " + describe(Duration.ofMillis(fragmentAgeMs)) + ".");
        }
        if (live && telemetryAgeSeconds != null && telemetryAgeSeconds > telemetryStaleAfter.getSeconds()) {
            reasons.add("Telemetry is " + describe(Duration.ofMillis(Math.round(telemetryAgeSeconds * 1000))) + " old.");
            status = Status.worst(status, Status.WARN);
        }
        if (Boolean.FALSE.equals(hostReachable)) {
            reasons.add("The camera host answers neither ping nor its TCP ports from this server.");
            status = Status.worst(status, Status.WARN);
        } else if (Boolean.TRUE.equals(hostReachable) && status == Status.DOWN) {
            reasons.add("The host answers, so the producer service on it is probably stopped.");
        }
        return new Evaluation(status, List.copyOf(reasons));
    }

    private ServerHealth serverHealth(Instant now, String databaseError) {
        List<String> reasons = new ArrayList<>();
        Status status = Status.OK;
        if (databaseError != null) {
            status = Status.DOWN;
            reasons.add("Database query failed: " + databaseError);
        }

        Long diskTotal = null;
        Long diskFree = null;
        try {
            FileStore store = Files.getFileStore(imagesDir);
            diskTotal = store.getTotalSpace();
            diskFree = store.getUsableSpace();
            if (diskTotal > 0 && (diskFree < 50L * 1024 * 1024 * 1024 || diskFree * 20 < diskTotal)) {
                reasons.add("The image disk is nearly full: " + (diskFree / (1024 * 1024 * 1024)) + " GB free.");
                status = Status.worst(status, Status.WARN);
            }
        } catch (IOException | RuntimeException e) {
            reasons.add("The image directory " + imagesDir + " is not accessible: " + e.getMessage());
            status = Status.worst(status, Status.DOWN);
        }

        Map<String, Object> archive;
        try {
            archive = autoCompressor.status();
        } catch (RuntimeException e) {
            archive = Map.of("error", e.toString());
        }

        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            hostname = null;
        }
        Instant started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        Integer viewers = asInteger(live.status().get("viewers"));
        return new ServerHealth(status, List.copyOf(reasons), hostname, started,
                Math.max(0, Duration.between(started, now).getSeconds()), databaseError == null, databaseError,
                imagesDir.toString(), diskTotal, diskFree, archive, viewers);
    }

    private CompletableFuture<HostProbe> probeAsync(String host, List<Integer> ports) {
        return CompletableFuture.supplyAsync(() -> probe(host.trim(), ports == null ? List.of() : ports), probes);
    }

    /**
     * Ping (ICMP when running as root, otherwise TCP echo) and one TCP connect per configured port,
     * all at the same time so a silent host costs one timeout rather than one per check.
     */
    private HostProbe probe(String host, List<Integer> ports) {
        int timeout = properties.getProbeTimeoutMs();
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return HostProbe.failed(host, "name does not resolve");
        }
        CompletableFuture<Long> ping = CompletableFuture.supplyAsync(() -> pingMillis(address, timeout), probes);
        Map<String, CompletableFuture<Long>> connects = new LinkedHashMap<>();
        for (Integer port : ports) {
            if (port != null) {
                connects.put(String.valueOf(port),
                        CompletableFuture.supplyAsync(() -> connectMillis(address, port, timeout), probes));
            }
        }
        Long latency = ping.join();
        boolean icmp = latency != null;
        Map<String, Boolean> tcp = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<Long>> entry : connects.entrySet()) {
            Long millis = entry.getValue().join();
            tcp.put(entry.getKey(), millis != null);
            if (latency == null) {
                latency = millis;
            }
        }
        boolean reachable = icmp || tcp.containsValue(true);
        return new HostProbe(host, address.getHostAddress(), reachable, icmp, tcp, latency, null);
    }

    /** Round-trip time of a ping, or null when the host did not answer within {@code timeout}. */
    private static Long pingMillis(InetAddress address, int timeout) {
        try {
            long started = System.nanoTime();
            return address.isReachable(timeout) ? (System.nanoTime() - started) / 1_000_000 : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Time to open a TCP connection, or null when the port did not accept one within {@code timeout}. */
    private static Long connectMillis(InetAddress address, int port, int timeout) {
        try (Socket socket = new Socket()) {
            long started = System.nanoTime();
            socket.connect(new InetSocketAddress(address, port), timeout);
            return (System.nanoTime() - started) / 1_000_000;
        } catch (IOException e) {
            return null;
        }
    }

    private HostProbe await(CompletableFuture<HostProbe> future) {
        if (future == null) {
            return null;
        }
        long budget = properties.getProbeTimeoutMs() * 2L + 2000;
        try {
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return HostProbe.failed("?", "probe timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HostProbe.failed("?", "probe interrupted");
        } catch (Exception e) {
            return HostProbe.failed("?", "probe failed: " + e.getMessage());
        }
    }

    /** "4 min", "3 h 12 min", "167 d 5 h" — for the reasons text. */
    static String describe(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + " h" + (minutes % 60 == 0 ? "" : " " + (minutes % 60) + " min");
        }
        long days = hours / 24;
        return days + " d" + (hours % 24 == 0 ? "" : " " + (hours % 24) + " h");
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant asInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
