package edu.camserver.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsServiceTest {
    @TempDir Path dir;

    private SettingsService service(Path file) {
        return new SettingsService(new ObjectMapper(), file.toString(), 100000, 60);
    }

    @Test void restoresExposureAndGainAfterRestart() {
        Path file = dir.resolve("settings.json");
        SettingsService first = service(file);
        first.update(50000, 70);
        assertEquals(Map.of("exposure", 50000, "gain", 70, "autoExposure", true), service(file).getSettings());
        first.update(null, 80);
        assertEquals(Map.of("exposure", 50000, "gain", 80, "autoExposure", true), service(file).getSettings());
    }

    @Test void manualOverrideSurvivesRestartAndPartialUpdates() {
        Path file = dir.resolve("settings.json");
        SettingsService first = service(file);
        first.update(2000, 1, false);
        SettingsService restored = service(file);
        assertEquals(false, restored.getSettings().get("autoExposure"));
        restored.update(null, 2);
        assertEquals(false, service(file).getSettings().get("autoExposure"));
        restored.update(null, null, true);
        assertEquals(Map.of("exposure", 2000, "gain", 2, "autoExposure", true), service(file).getSettings());
    }

    @Test void legacySavedSettingsBecomeTheAutomaticExposureCeiling() throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{\"exposure\":150000,\"gain\":1}");
        assertEquals(Map.of("exposure", 150000, "gain", 1, "autoExposure", true), service(file).getSettings());
    }

    @Test void invalidInputDoesNotChangeSavedOrLiveSettings() {
        Path file = dir.resolve("settings.json");
        SettingsService first = service(file);
        first.update(50000, 70);
        assertThrows(ResponseStatusException.class, () -> first.update(0, 60));
        assertThrows(ResponseStatusException.class, () -> first.update(100000, 101));
        assertEquals(first.getSettings(), service(file).getSettings());
        assertEquals(50000, first.getSettings().get("exposure"));
        assertThrows(UnsupportedOperationException.class, () -> first.getSettings().put("gain", 1));
    }

    @Test void corruptFileUsesConfiguredDefaults() throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{broken");
        assertEquals(Map.of("exposure", 100000, "gain", 60, "autoExposure", true), service(file).getSettings());
    }

    @Test void writeFailureDoesNotClaimSettingsWereSaved() throws Exception {
        Path parent = dir.resolve("not-a-directory");
        Files.writeString(parent, "existing data");
        SettingsService first = service(parent.resolve("settings.json"));
        assertThrows(ResponseStatusException.class, () -> first.update(50000, 70));
        assertEquals(Map.of("exposure", 100000, "gain", 60, "autoExposure", true), first.getSettings());
    }
}
