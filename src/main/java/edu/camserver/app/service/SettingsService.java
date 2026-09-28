package edu.camserver.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@Service
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private final ObjectMapper mapper;
    private final Path settingsFile;
    private Map<String, Object> settings;

    public SettingsService(ObjectMapper mapper,
                           @Value("${app.live.settings-file:./data/seeing-monitor-settings.json}") String settingsFile,
                           @Value("${app.live.default-exposure-us:100000}") int exposure,
                           @Value("${app.live.default-gain:60}") int gain) {
        this.mapper = mapper;
        this.settingsFile = Path.of(settingsFile).toAbsolutePath();
        validate(exposure, gain);
        this.settings = Map.of("exposure", exposure, "gain", gain, "autoExposure", true);
        if (Files.exists(this.settingsFile)) {
            try {
                Map<String, Object> saved = mapper.readValue(this.settingsFile.toFile(), new TypeReference<>() {});
                Integer savedExposure = (Integer) saved.get("exposure");
                Integer savedGain = (Integer) saved.get("gain");
                if (savedExposure == null || savedGain == null) throw new IllegalArgumentException("Missing camera settings");
                Object savedAuto = saved.getOrDefault("autoExposure", true);
                if (!(savedAuto instanceof Boolean)) throw new IllegalArgumentException("Invalid auto exposure mode");
                validate(savedExposure, savedGain);
                this.settings = Map.of("exposure", savedExposure, "gain", savedGain, "autoExposure", savedAuto);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not restore seeing-monitor settings from {}: {}. Using configured defaults.", this.settingsFile, e.toString());
            }
        }
    }

    public synchronized Map<String, Object> getSettings() { return settings; }

    public synchronized void update(Integer exposure, Integer gain) {
        update(exposure, gain, null);
    }

    public synchronized void update(Integer exposure, Integer gain, Boolean autoExposure) {
        int nextExposure = exposure == null ? (Integer) settings.get("exposure") : exposure;
        int nextGain = gain == null ? (Integer) settings.get("gain") : gain;
        boolean nextAuto = autoExposure == null ? (Boolean) settings.get("autoExposure") : autoExposure;
        validate(nextExposure, nextGain);
        Map<String, Object> next = Map.of("exposure", nextExposure, "gain", nextGain, "autoExposure", nextAuto);
        Path temporary = null;
        try {
            Files.createDirectories(settingsFile.getParent());
            temporary = Files.createTempFile(settingsFile.getParent(), "seeing-settings-", ".tmp");
            mapper.writeValue(temporary.toFile(), next);
            try {
                Files.move(temporary, settingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
            settings = next;
        } catch (IOException e) {
            log.error("Cannot save seeing-monitor settings to {}", settingsFile, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to save camera settings", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException e) {
                    log.warn("Cannot remove temporary settings file {}", temporary);
                }
            }
        }
    }

    private static void validate(int exposure, int gain) {
        if (exposure < 100 || exposure > 10_000_000 || gain < 1 || gain > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Exposure must be 100–10,000,000 microseconds and gain must be 1–100");
        }
    }
}
