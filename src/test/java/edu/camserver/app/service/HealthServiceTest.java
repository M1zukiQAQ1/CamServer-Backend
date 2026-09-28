package edu.camserver.app.service;

import edu.camserver.app.service.HealthService.Evaluation;
import edu.camserver.app.service.HealthService.HealthReport;
import edu.camserver.app.service.HealthService.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T02:00:00Z");
    private static final Duration WARN = Duration.ofMinutes(20);
    private static final Duration DOWN = Duration.ofMinutes(60);
    private static final Duration INACTIVE = Duration.ofDays(30);

    @Test
    void freshUploadsFromAReachableHostAreOk() {
        Evaluation result = HealthService.evaluateCamera(NOW, NOW.minus(Duration.ofMinutes(4)), true, true, 512_000L,
                WARN, DOWN, INACTIVE);
        assertEquals(Status.OK, result.status());
        assertTrue(result.reasons().isEmpty());
    }

    @Test
    void lateUploadsWarn() {
        Evaluation result = HealthService.evaluateCamera(NOW, NOW.minus(Duration.ofMinutes(25)), true, true, 512_000L,
                WARN, DOWN, INACTIVE);
        assertEquals(Status.WARN, result.status());
        assertEquals("Last frame 25 min ago, later than the expected 20 min.", result.reasons().get(0));
    }

    @Test
    void stoppedUploadsFromAnUnreachableHostAreDown() {
        Evaluation result = HealthService.evaluateCamera(NOW, NOW.minus(Duration.ofDays(167)), true, false, 0L,
                WARN, DOWN, INACTIVE);
        assertEquals(Status.DOWN, result.status());
        assertEquals(3, result.reasons().size());
        assertTrue(result.reasons().get(0).startsWith("Last frame 167 d"));
        assertTrue(result.reasons().get(1).contains("neither ping"));
        assertTrue(result.reasons().get(2).contains("empty"));
    }

    @Test
    void stoppedUploadsFromAReachableHostBlameTheService() {
        Evaluation result = HealthService.evaluateCamera(NOW, NOW.minus(Duration.ofHours(3)), true, true, 400L,
                WARN, DOWN, INACTIVE);
        assertEquals(Status.DOWN, result.status());
        assertTrue(result.reasons().get(1).contains("capture service"));
    }

    @Test
    void unmonitoredCameraWithOldFramesIsInactiveNotDown() {
        Evaluation result = HealthService.evaluateCamera(NOW, NOW.minus(Duration.ofDays(400)), false, null, 300L,
                WARN, DOWN, INACTIVE);
        assertEquals(Status.INACTIVE, result.status());
    }

    @Test
    void unmonitoredCameraThatJustStoppedIsStillDown() {
        Evaluation result = HealthService.evaluateCamera(NOW, NOW.minus(Duration.ofHours(2)), false, null, 300L,
                WARN, DOWN, INACTIVE);
        assertEquals(Status.DOWN, result.status());
    }

    @Test
    void silentHostOnlyWarnsWhileUploadsAreFresh() {
        Evaluation result = HealthService.evaluateCamera(NOW, NOW.minus(Duration.ofMinutes(2)), true, false, 300L,
                WARN, DOWN, INACTIVE);
        assertEquals(Status.WARN, result.status());
    }

    @Test
    void cameraWithoutFrames() {
        assertEquals(Status.DOWN, HealthService.evaluateCamera(NOW, null, true, true, null, WARN, DOWN, INACTIVE).status());
        assertEquals(Status.INACTIVE, HealthService.evaluateCamera(NOW, null, false, null, null, WARN, DOWN, INACTIVE).status());
    }

    @Test
    void seeingMonitorStates() {
        Duration stale = Duration.ofSeconds(10);
        Duration telemetryStale = Duration.ofSeconds(30);
        assertEquals(Status.OK, HealthService.evaluateSeeing(true, 150L, 0.8, true, stale, telemetryStale, null).status());

        Evaluation stalled = HealthService.evaluateSeeing(true, 45_000L, 1.0, true, stale, telemetryStale, null);
        assertEquals(Status.WARN, stalled.status());
        assertTrue(stalled.reasons().get(0).contains("no video for 45 s"));

        Evaluation offline = HealthService.evaluateSeeing(false, null, null, true, stale, telemetryStale, "producer closed the connection");
        assertEquals(Status.DOWN, offline.status());
        assertTrue(offline.reasons().get(0).contains("producer closed the connection"));
        assertTrue(offline.reasons().get(1).contains("producer service"));

        Evaluation hostGone = HealthService.evaluateSeeing(false, null, null, false, stale, telemetryStale, null);
        assertEquals(Status.DOWN, hostGone.status());
        assertTrue(hostGone.reasons().get(1).contains("neither ping"));
    }

    @Test
    void durationsAreDescribedCompactly() {
        assertEquals("45 s", HealthService.describe(Duration.ofSeconds(45)));
        assertEquals("4 min", HealthService.describe(Duration.ofMinutes(4)));
        assertEquals("3 h 12 min", HealthService.describe(Duration.ofMinutes(192)));
        assertEquals("2 h", HealthService.describe(Duration.ofHours(2)));
        assertEquals("167 d 5 h", HealthService.describe(Duration.ofHours(167 * 24 + 5)));
    }

    @Test
    void recentReportsArePassedThrough() {
        HealthReport report = report(NOW.minusSeconds(150), Status.OK);
        assertSame(report, HealthService.markIfStale(report, NOW));
    }

    @Test
    void reportsOlderThanThreeIntervalsAreStale() {
        HealthReport ok = HealthService.markIfStale(report(NOW.minusSeconds(181), Status.OK), NOW);
        assertTrue(ok.stale());
        assertEquals(Status.UNKNOWN, ok.status());

        HealthReport down = HealthService.markIfStale(report(NOW.minusSeconds(600), Status.DOWN), NOW);
        assertTrue(down.stale());
        assertEquals(Status.DOWN, down.status());
        assertFalse(report(NOW, Status.OK).stale());
    }

    private static HealthReport report(Instant generatedAt, Status status) {
        return new HealthReport(generatedAt, 60, 60, false, status, Map.of(), List.of(), null, null);
    }

    @Test
    void worstStatusIgnoresInactive() {
        assertEquals(Status.OK, Status.worst(Status.OK, Status.INACTIVE));
        assertEquals(Status.WARN, Status.worst(Status.INACTIVE, Status.WARN));
        assertEquals(Status.DOWN, Status.worst(Status.WARN, Status.DOWN));
        assertEquals(Status.DOWN, Status.worst(Status.DOWN, Status.WARN));
    }
}
