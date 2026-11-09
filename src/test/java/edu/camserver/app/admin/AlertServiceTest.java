package edu.camserver.app.admin;

import edu.camserver.app.service.HealthService.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AlertServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-28T22:00:00Z");

    private static AlertSettings settings(boolean enabled, boolean onWarn, int repeatHours, List<String> muted) {
        return new AlertSettings(enabled, List.of("ops@example.edu"), "smtp.ucsb.edu", 25, "starttls", null, null,
                "All Sky Cam <allsky-alerts@deepspace.ucsb.edu>", 10, onWarn, repeatHours, muted);
    }

    private static List<AlertService.Component> seeing(Status status) {
        return List.of(new AlertService.Component("seeing", "Seeing Monitor", status, List.of("reason")));
    }

    @Test void shortOutagesSuchAsDeployRestartsDoNotAlert() {
        Map<String, AlertService.Tracked> state = new LinkedHashMap<>();
        AlertSettings on = settings(true, false, 24, List.of());
        assertTrue(AlertService.decide(state, seeing(Status.OK), on, T0).isEmpty());
        assertTrue(AlertService.decide(state, seeing(Status.DOWN), on, T0.plusSeconds(60)).isEmpty());
        assertTrue(AlertService.decide(state, seeing(Status.OK), on, T0.plusSeconds(120)).isEmpty());
        assertNull(state.get("seeing").alertedStatus());
    }

    @Test void downLongerThanTheDelayAlertsOnceThenRecovers() {
        Map<String, AlertService.Tracked> state = new LinkedHashMap<>();
        AlertSettings on = settings(true, false, 0, List.of());
        AlertService.decide(state, seeing(Status.DOWN), on, T0);
        assertTrue(AlertService.decide(state, seeing(Status.DOWN), on, T0.plus(Duration.ofMinutes(9))).isEmpty());
        List<AlertService.Change> fired = AlertService.decide(state, seeing(Status.DOWN), on, T0.plus(Duration.ofMinutes(10)));
        assertEquals(1, fired.size());
        assertEquals(AlertService.Kind.PROBLEM, fired.get(0).kind());
        assertEquals(T0, fired.get(0).since());
        assertTrue(AlertService.decide(state, seeing(Status.DOWN), on, T0.plus(Duration.ofDays(3))).isEmpty(), "no reminders when 0");
        List<AlertService.Change> recovered = AlertService.decide(state, seeing(Status.OK), on, T0.plus(Duration.ofDays(3)));
        assertEquals(AlertService.Kind.RECOVERED, recovered.get(0).kind());
        assertTrue(AlertService.decide(state, seeing(Status.OK), on, T0.plus(Duration.ofDays(4))).isEmpty());
    }

    @Test void remindersFollowTheConfiguredInterval() {
        Map<String, AlertService.Tracked> state = new LinkedHashMap<>();
        AlertSettings on = settings(true, false, 24, List.of());
        AlertService.decide(state, seeing(Status.DOWN), on, T0);
        assertEquals(1, AlertService.decide(state, seeing(Status.DOWN), on, T0.plus(Duration.ofMinutes(10))).size());
        assertTrue(AlertService.decide(state, seeing(Status.DOWN), on, T0.plus(Duration.ofHours(23))).isEmpty());
        List<AlertService.Change> reminder = AlertService.decide(state, seeing(Status.DOWN), on, T0.plus(Duration.ofMinutes(10)).plus(Duration.ofHours(24)));
        assertEquals(AlertService.Kind.REMINDER, reminder.get(0).kind());
    }

    @Test void warningsOnlyAlertWhenEnabledAndMutedOrDisabledNeverDo() {
        Map<String, AlertService.Tracked> state = new LinkedHashMap<>();
        AlertService.decide(state, seeing(Status.WARN), settings(true, false, 24, List.of()), T0);
        assertTrue(AlertService.decide(state, seeing(Status.WARN), settings(true, false, 24, List.of()), T0.plus(Duration.ofHours(1))).isEmpty());
        assertEquals(1, AlertService.decide(state, seeing(Status.WARN), settings(true, true, 24, List.of()), T0.plus(Duration.ofHours(1))).size());

        Map<String, AlertService.Tracked> muted = new LinkedHashMap<>();
        AlertService.decide(muted, seeing(Status.DOWN), settings(true, false, 24, List.of("seeing")), T0);
        assertTrue(AlertService.decide(muted, seeing(Status.DOWN), settings(true, false, 24, List.of("seeing")), T0.plus(Duration.ofHours(1))).isEmpty());

        Map<String, AlertService.Tracked> off = new LinkedHashMap<>();
        AlertService.decide(off, seeing(Status.DOWN), settings(false, false, 24, List.of()), T0);
        assertTrue(AlertService.decide(off, seeing(Status.DOWN), settings(false, false, 24, List.of()), T0.plus(Duration.ofHours(1))).isEmpty());
        // Enabling later reports what has been down all along, with its real start time.
        List<AlertService.Change> late = AlertService.decide(off, seeing(Status.DOWN), settings(true, false, 24, List.of()), T0.plus(Duration.ofHours(2)));
        assertEquals(T0, late.get(0).since());
    }

    @Test void escalationFromWarnToDownIsReportedAgain() {
        Map<String, AlertService.Tracked> state = new LinkedHashMap<>();
        AlertSettings onWarn = settings(true, true, 0, List.of());
        AlertService.decide(state, seeing(Status.WARN), onWarn, T0);
        assertEquals(1, AlertService.decide(state, seeing(Status.WARN), onWarn, T0.plus(Duration.ofMinutes(10))).size());
        AlertService.decide(state, seeing(Status.DOWN), onWarn, T0.plus(Duration.ofMinutes(11)));
        List<AlertService.Change> down = AlertService.decide(state, seeing(Status.DOWN), onWarn, T0.plus(Duration.ofMinutes(21)));
        assertEquals(Status.DOWN, down.get(0).status());
    }

    @Test void settingsValidationKeepsTheStoredSmtpPassword() {
        AlertSettings stored = new AlertSettings(true, List.of("ops@example.edu"), "smtp.gmail.com", 587, "starttls-required",
                "sender@example.edu", "app-password", "sender@example.edu", 10, false, 24, List.of());
        AlertSettings edited = new AlertSettings(true, List.of("ops@example.edu", " "), "smtp.gmail.com", 587, "starttls-required",
                "sender@example.edu", "", "sender@example.edu", 15, false, 24, List.of());
        AlertSettings merged = edited.keepingPassword(stored).validated();
        assertEquals("app-password", merged.password());
        assertEquals(List.of("ops@example.edu"), merged.recipients());
        assertEquals(false, merged.redacted().containsValue("app-password"));
        assertThrows(IllegalArgumentException.class, () -> new AlertSettings(true, List.of(), "smtp.ucsb.edu", 25, "starttls",
                null, null, "a@b.edu", 10, false, 24, List.of()).validated());
        assertThrows(IllegalArgumentException.class, () -> new AlertSettings(false, List.of("not an address"), "smtp.ucsb.edu", 25,
                "starttls", null, null, "a@b.edu", 10, false, 24, List.of()).validated());
    }
}
