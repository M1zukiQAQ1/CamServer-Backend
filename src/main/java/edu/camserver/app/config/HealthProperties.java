package edu.camserver.app.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * What the health panel ({@code GET /api/health}) knows about each camera host that the database
 * does not: where the host is, which ports answer, and how stale its uploads may get before the
 * camera is reported as warning or down. Bound from {@code app.health}.
 */
@Component
@ConfigurationProperties(prefix = "app.health")
@Getter
@Setter
public class HealthProperties {

    /**
     * Seconds between two background checks (hosts probed, database read); requests only read the
     * newest result. At least 10.
     */
    private int refreshSeconds = 60;

    /** Per host: ping and each TCP connect wait at most this long. */
    private int probeTimeoutMs = 2500;

    /** A camera whose newest frame is older than this is reported as WARN ... */
    private int warnAfterMinutes = 20;

    /** ... and older than this as DOWN. */
    private int downAfterMinutes = 60;

    /**
     * A camera with no configured host and no frame for this long is INACTIVE (retired or
     * never connected) rather than DOWN, so it does not count against the overall status.
     */
    private int inactiveAfterDays = 30;

    private List<CameraEntry> cameras = new ArrayList<>();

    private Seeing seeing = new Seeing();

    @Getter
    @Setter
    public static class CameraEntry {
        /** The {@code Cameras.CamId} value (trimmed). */
        private String cameraId;
        /** Host name or IP of the capture computer; blank when nothing is known. */
        private String host;
        /** TCP ports that should accept a connection on the host (e.g. 22). */
        private List<Integer> ports = new ArrayList<>();
        /** Free text shown on the panel. */
        private String note;
        /** Per-camera overrides of the global thresholds; null uses the global value. */
        private Integer warnAfterMinutes;
        private Integer downAfterMinutes;
    }

    @Getter
    @Setter
    public static class Seeing {
        private String label = "Seeing Monitor";
        private String host;
        private List<Integer> ports = new ArrayList<>();
        /** A live session whose newest video fragment is older than this is WARN. */
        private int staleAfterSeconds = 10;
        /** Telemetry older than this is reported. */
        private int telemetryStaleAfterSeconds = 30;
        private String note;
    }
}
