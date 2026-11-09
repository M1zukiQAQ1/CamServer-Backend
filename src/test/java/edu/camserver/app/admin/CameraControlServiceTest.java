package edu.camserver.app.admin;

import edu.camserver.app.admin.CameraControlService.CommandRejected;
import edu.camserver.app.admin.CameraControlService.CommandType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CameraControlServiceTest {
    @TempDir Path dir;

    private MutableClock clock;
    private CameraControlService cameras;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-06T20:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }

    private static final Map<String, Object> REPORT = Map.of(
            "capabilities", List.of("CAPTURE", "RESTART_SERVICE", "REBOOT", "SHUTDOWN"),
            "sensors", Map.of("temperature", 21.5, "humidity", 40.0),
            "host", Map.of("cpuTemperature", 55.1, "ips", List.of("128.111.23.98")));

    @BeforeEach void setUp() throws Exception {
        Files.writeString(dir.resolve("devices.json"), """
                {"devices": [{"id": "allskycam2", "name": "Broida all-sky", "tokenSha256": "%s"}]}
                """.formatted(CameraControlService.sha256("secret-token")));
        clock = new MutableClock();
        cameras = new CameraControlService(dir.resolve("devices.json"), clock);
    }

    @Test void authenticatesOnlyKnownTokens() {
        assertEquals("allskycam2", cameras.authenticate("secret-token").orElseThrow());
        assertTrue(cameras.authenticate("wrong").isEmpty());
        assertTrue(cameras.authenticate(null).isEmpty());
    }

    @Test void listsRegisteredHostBeforeItReports() {
        Map<String, Object> view = cameras.overview().get(0);
        assertEquals("Broida all-sky", view.get("name"));
        assertEquals(false, view.get("online"));
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.CAPTURE, null, "admin"));
    }

    @Test void deliversCommandOnceAndRecordsResult() {
        cameras.heartbeat("allskycam2", REPORT, "128.111.23.98");
        CameraControlService.Command capture = cameras.queue("allskycam2", CommandType.CAPTURE, null, "128.111.180.9");

        List<Map<String, Object>> delivered = cameras.heartbeat("allskycam2", REPORT, "128.111.23.98");
        assertEquals(List.of(Map.of("id", capture.id, "type", "CAPTURE")), delivered);
        assertTrue(cameras.heartbeat("allskycam2", REPORT, "128.111.23.98").isEmpty(), "at most once");

        cameras.heartbeat("allskycam2", Map.of("results", List.of(
                Map.of("id", capture.id, "ok", true, "running", true, "message", "exposing"))), "x");
        assertEquals(CameraControlService.CommandStatus.DELIVERED, capture.status);
        cameras.heartbeat("allskycam2", Map.of("capabilities", REPORT.get("capabilities"), "results", List.of(
                Map.of("id", capture.id, "ok", true, "message", "uploaded", "imgId", 75500))), "x");
        assertEquals(CameraControlService.CommandStatus.DONE, capture.status);
        assertEquals(Map.of("imgId", 75500), capture.result);
        assertEquals("uploaded", capture.message);
    }

    @Test void commandsExpireWhenNotPickedUp() {
        cameras.heartbeat("allskycam2", REPORT, "x");
        CameraControlService.Command reboot = cameras.queue("allskycam2", CommandType.REBOOT, "allskycam2", "admin");
        clock.advance(CameraControlService.COMMAND_TTL.plusSeconds(1));
        assertTrue(cameras.heartbeat("allskycam2", REPORT, "x").isEmpty(), "a stale reboot must never be delivered");
        assertEquals(CameraControlService.CommandStatus.EXPIRED, reboot.status);
    }

    @Test void powerCommandsNeedTheHostNameAndExcludeEachOther() {
        cameras.heartbeat("allskycam2", REPORT, "x");
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.SHUTDOWN, null, "admin"));
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.SHUTDOWN, "seeingcam", "admin"));
        cameras.queue("allskycam2", CommandType.REBOOT, "allskycam2", "admin");
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.SHUTDOWN, "allskycam2", "admin"));
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.CAPTURE, null, "admin"));
    }

    @Test void rejectsUnsupportedCommands() {
        cameras.heartbeat("allskycam2", Map.of("capabilities", List.of("REBOOT")), "x");
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.CAPTURE, null, "admin"));
        assertThrows(CommandRejected.class, () -> cameras.queue("seeingcam", CommandType.CAPTURE, null, "admin"));
    }

    @Test void keepsSensorHistoryAtTheSampleInterval() {
        cameras.heartbeat("allskycam2", REPORT, "x");
        clock.advance(Duration.ofSeconds(5));
        cameras.heartbeat("allskycam2", REPORT, "x");
        clock.advance(CameraControlService.SAMPLE_EVERY);
        cameras.heartbeat("allskycam2", REPORT, "x");
        List<?> history = (List<?>) cameras.overview().get(0).get("history");
        assertEquals(2, history.size());
        CameraControlService.Sample sample = (CameraControlService.Sample) history.get(0);
        assertEquals(21.5, sample.temperature());
        assertEquals(55.1, sample.cpuTemperature());
        assertEquals(true, cameras.overview().get(0).get("online"));
        clock.advance(CameraControlService.OFFLINE_AFTER.plusSeconds(1));
        assertEquals(false, cameras.overview().get(0).get("online"));
    }

    @Test void heaterCommandsCarryValidatedSettingsAndTheLatestWins() {
        cameras.heartbeat("allskycam2", Map.of("capabilities", List.of("REBOOT", "HEATER")), "x");
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.HEATER, null, "admin", Map.of()));
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.HEATER, null, "admin",
                Map.of("mode", "WARM")));
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.HEATER, null, "admin",
                Map.of("mode", "AUTO", "target", 200)));

        CameraControlService.Command on = cameras.queue("allskycam2", CommandType.HEATER, null, "admin", Map.of("mode", "on"));
        CameraControlService.Command auto = cameras.queue("allskycam2", CommandType.HEATER, null, "admin",
                Map.of("mode", "AUTO", "target", 18.04));
        assertEquals(CameraControlService.CommandStatus.SUPERSEDED, on.status);
        List<Map<String, Object>> delivered = cameras.heartbeat("allskycam2", Map.of("capabilities", List.of("HEATER")), "x");
        assertEquals(List.of(Map.of("id", auto.id, "type", "HEATER", "mode", "AUTO", "target", 18.0)), delivered);

        // a delivered one is not replaced, the next simply follows it; power commands still exclude it
        CameraControlService.Command off = cameras.queue("allskycam2", CommandType.HEATER, null, "admin", Map.of("mode", "OFF"));
        assertEquals(CameraControlService.CommandStatus.DELIVERED, auto.status);
        assertEquals(CameraControlService.CommandStatus.PENDING, off.status);
        assertEquals(Map.of("mode", "OFF"), off.params);
    }

    @Test void heaterNeedsTheCapability() {
        cameras.heartbeat("allskycam2", REPORT, "x");
        assertThrows(CommandRejected.class, () -> cameras.queue("allskycam2", CommandType.HEATER, null, "admin",
                Map.of("mode", "ON")));
    }

    @Test void picksUpNewDevicesFileWithoutRestart() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, """
                {"devices": [{"id": "seeingcam", "tokenSha256": "%s"}]}
                """.formatted(CameraControlService.sha256("other")));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(clock.now.plusSeconds(60)));
        assertEquals("seeingcam", cameras.authenticate("other").orElseThrow());
        assertTrue(cameras.authenticate("secret-token").isEmpty());
    }
}
