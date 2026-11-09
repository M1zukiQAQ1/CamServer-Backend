package edu.camserver.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.camserver.app.config.HealthProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SeeingNightCheckTest {
    @TempDir Path dir;

    private static final double LAT = 34.4133, LON = -119.861;

    @Test void sunAltitudeMatchesKnownEphemeris() {
        // Santa Barbara, 2026-09-28: sunset ~18:48 PDT (01:48 UTC), solar noon ~12:49 PDT at ~53.5 deg.
        assertEquals(-0.83, SunPosition.altitude(LAT, LON, Instant.parse("2026-09-29T01:48:00Z")), 0.5);
        assertEquals(53.5, SunPosition.altitude(LAT, LON, Instant.parse("2026-09-28T19:49:00Z")), 1.0);
        assertTrue(SunPosition.altitude(LAT, LON, Instant.parse("2026-09-28T08:00:00Z")) < -40);
    }

    @Test void lastCompletedNightIsTheNightBeforeTodayAtAnyTimeOfDay() {
        SunPosition.Night afternoon = SunPosition.lastCompletedNight(LAT, LON, -12, Instant.parse("2026-09-28T21:40:00Z")).orElseThrow();
        // Nautical dusk ~19:45 PDT on the 27th, dawn ~06:00 PDT on the 28th (5-minute resolution).
        assertTrue(Duration.between(Instant.parse("2026-09-28T02:45:00Z"), afternoon.start()).abs().toMinutes() <= 10, "start " + afternoon.start());
        assertTrue(Duration.between(Instant.parse("2026-09-28T13:00:00Z"), afternoon.end()).abs().toMinutes() < 20, "end " + afternoon.end());
        // During the night the previous one is judged, not the one in progress.
        SunPosition.Night midnight = SunPosition.lastCompletedNight(LAT, LON, -12, Instant.parse("2026-09-29T07:00:00Z")).orElseThrow();
        assertEquals(afternoon, midnight);
    }

    @Test void nightWithoutUsableRecordsWarnsWithCountsAndSettings() throws Exception {
        Path history = dir.resolve("history");
        Files.createDirectories(history);
        // Two records inside the night of Sep 27/28, one outside it, none usable.
        Files.writeString(history.resolve("2026-09-28.jsonl"),
                "{\"timestamp\":1790571600,\"status\":\"searching\",\"sessionId\":\"a\",\"exposureUs\":1000,\"gain\":50}\n"
                + "{\"timestamp\":1790571630,\"status\":\"collecting\",\"sessionId\":\"a\",\"exposureUs\":1000,\"gain\":50}\n"
                + "{\"timestamp\":1790622000,\"status\":\"relative\",\"sessionId\":\"a\",\"exposureUs\":1000,\"gain\":50}\n");
        SeeingNightCheck check = new SeeingNightCheck(new HealthProperties(),
                new SeeingHistoryService(new ObjectMapper(), history.toString()));
        SeeingNightCheck.NightSummary night = check.lastNight(Instant.parse("2026-09-28T21:40:00Z")).orElseThrow();
        assertEquals(2, night.records());
        assertEquals(0, night.validRecords());
        HealthService.Evaluation evaluation = SeeingNightCheck.evaluate(night,
                Map.of("exposure", 1000, "gain", 50, "autoExposure", false));
        assertEquals(HealthService.Status.WARN, evaluation.status());
        String reason = evaluation.reasons().get(0);
        assertTrue(reason.contains("searching 1") && reason.contains("collecting 1"), reason);
        assertTrue(reason.contains("Exposure 1000 µs, gain 50") && reason.contains("manual 1000 µs"), reason);
        assertTrue(reason.contains("(Sep 27 19:") && reason.contains("PDT)"), reason);
    }

    @Test void oneUsableRecordIsEnoughAndAnEmptyNightSaysTheProducerWasNotRecording() {
        Instant start = Instant.parse("2026-09-28T02:40:00Z"), end = Instant.parse("2026-09-28T13:00:00Z");
        SeeingNightCheck.NightSummary good = new SeeingNightCheck.NightSummary(start, end, "x", 1200, 1,
                Map.of("relative", 1L, "searching", 1199L), Map.of("10000", 1200L), Map.of("50", 1200L), end);
        assertEquals(HealthService.Status.OK, SeeingNightCheck.evaluate(good, Map.of()).status());
        SeeingNightCheck.NightSummary empty = new SeeingNightCheck.NightSummary(start, end, "x", 0, 0, Map.of(), Map.of(), Map.of(), end);
        assertTrue(SeeingNightCheck.evaluate(empty, Map.of()).reasons().get(0).contains("not recording"));
        assertEquals(HealthService.Status.OK, SeeingNightCheck.evaluate(null, Map.of()).status());
    }
}
