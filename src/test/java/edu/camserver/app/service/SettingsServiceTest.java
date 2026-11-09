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

    @Test void storedManualSettingIsKeptAsItIsWhenTheFileFormatChanges() throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{\"autoExposure\":false,\"gain\":50,\"exposure\":1000}");
        SettingsService legacy = service(file);
        assertEquals(Map.of("exposure", 1000, "gain", 50, "autoExposure", false), legacy.getSettings());
        assertEquals(Map.of("gain", 3.0, "frames", 1), legacy.getPreview());
        legacy.updatePreview(4.5, 3);
        String saved = Files.readString(file);
        // Older backends read the flat measurement keys, so a rollback keeps the camera setting.
        assertTrue(saved.contains("\"exposure\":1000") && saved.contains("\"autoExposure\":false") && saved.contains("\"preview\""), saved);
        SettingsService restored = service(file);
        assertEquals(Map.of("exposure", 1000, "gain", 50, "autoExposure", false), restored.getSettings());
        assertEquals(Map.of("gain", 4.5, "frames", 3), restored.getPreview());
    }

    @Test void pictureSettingsNeverChangeTheMeasurement() {
        Path file = dir.resolve("settings.json");
        SettingsService settings = service(file);
        settings.update(10000, 50, true);
        settings.updatePreview(6.0, 8);
        settings.updatePreview(null, 1);
        assertEquals(Map.of("exposure", 10000, "gain", 50, "autoExposure", true), service(file).getSettings());
        assertEquals(Map.of("gain", 6.0, "frames", 1), service(file).getPreview());
        assertThrows(ResponseStatusException.class, () -> settings.updatePreview(0.5, 1));
        assertThrows(ResponseStatusException.class, () -> settings.updatePreview(2.0, 9));
        assertThrows(ResponseStatusException.class, () -> settings.updatePreview(Double.NaN, 1));
        assertEquals(Map.of("gain", 6.0, "frames", 1), settings.getPreview());
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
