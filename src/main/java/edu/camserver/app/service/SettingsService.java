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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeing-monitor settings, persisted in one JSON file.
 *
 * <p>Two independent groups:
 * <ul>
 *   <li><b>measurement</b> — exposure, gain and automatic exposure of the camera itself. Every
 *       frame is captured with these and they decide whether Polaris can be measured, so only
 *       administrators change them (admin panel). {@link #getSettings()} returns this group; it is
 *       what the producer applies.</li>
 *   <li><b>preview</b> — how the live picture is rendered from those same frames: display
 *       stretch ({@code gain}, 1 = linear … 6) and how many consecutive frames are averaged
 *       ({@code frames}). Anyone on the seeing-monitor page may change them; they never reach
 *       the measurement.</li>
 * </ul>
 * The file keeps the measurement at the top level ({@code exposure/gain/autoExposure}, the
 * format older backends read, so a rollback keeps the camera setting) plus a {@code preview} object.
 */
@Service
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    public static final double MIN_PREVIEW_GAIN = 1.0;
    public static final double MAX_PREVIEW_GAIN = 6.0;
    public static final int MAX_PREVIEW_FRAMES = 8;
    private static final Map<String, Object> DEFAULT_PREVIEW = Map.of("gain", 3.0, "frames", 1);

    private final ObjectMapper mapper;
    private final Path settingsFile;
    private Map<String, Object> settings;
    private Map<String, Object> preview = DEFAULT_PREVIEW;

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
                Object nested = saved.get("measurement");
                @SuppressWarnings("unchecked")
                Map<String, Object> measurement = nested instanceof Map<?, ?> map ? (Map<String, Object>) map : saved;
                Integer savedExposure = (Integer) measurement.get("exposure");
                Integer savedGain = (Integer) measurement.get("gain");
                if (savedExposure == null || savedGain == null) throw new IllegalArgumentException("Missing camera settings");
                Object savedAuto = measurement.getOrDefault("autoExposure", true);
                if (!(savedAuto instanceof Boolean)) throw new IllegalArgumentException("Invalid auto exposure mode");
                validate(savedExposure, savedGain);
                this.settings = Map.of("exposure", savedExposure, "gain", savedGain, "autoExposure", savedAuto);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not restore seeing-monitor settings from {}: {}. Using configured defaults.", this.settingsFile, e.toString());
            }
            try {
                Map<String, Object> saved = mapper.readValue(this.settingsFile.toFile(), new TypeReference<>() {});
                if (saved.get("preview") instanceof Map<?, ?> map) {
                    double previewGain = ((Number) map.get("gain")).doubleValue();
                    int frames = ((Number) map.get("frames")).intValue();
                    validatePreview(previewGain, frames);
                    this.preview = Map.of("gain", previewGain, "frames", frames);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not restore live-picture settings from {}: {}. Using defaults.", this.settingsFile, e.toString());
            }
        }
    }

    /** The measurement (camera) settings: {@code exposure}, {@code gain}, {@code autoExposure}. */
    public synchronized Map<String, Object> getSettings() { return settings; }

    /** The live-picture settings: {@code gain} (display stretch) and {@code frames} (averaged). */
    public synchronized Map<String, Object> getPreview() { return preview; }

    public synchronized void update(Integer exposure, Integer gain) {
        update(exposure, gain, null);
    }

    /** Changes the measurement settings. Callers must be administrators. */
    public synchronized void update(Integer exposure, Integer gain, Boolean autoExposure) {
        int nextExposure = exposure == null ? (Integer) settings.get("exposure") : exposure;
        int nextGain = gain == null ? (Integer) settings.get("gain") : gain;
        boolean nextAuto = autoExposure == null ? (Boolean) settings.get("autoExposure") : autoExposure;
        validate(nextExposure, nextGain);
        Map<String, Object> next = Map.of("exposure", nextExposure, "gain", nextGain, "autoExposure", nextAuto);
        save(next, preview);
        settings = next;
    }

    /** Changes how the live picture is rendered; the measurement is unaffected. */
    public synchronized void updatePreview(Double gain, Integer frames) {
        double nextGain = gain == null ? ((Number) preview.get("gain")).doubleValue() : gain;
        int nextFrames = frames == null ? ((Number) preview.get("frames")).intValue() : frames;
        validatePreview(nextGain, nextFrames);
        Map<String, Object> next = Map.of("gain", nextGain, "frames", nextFrames);
        save(settings, next);
        preview = next;
    }

    private void save(Map<String, Object> measurement, Map<String, Object> livePicture) {
        Path temporary = null;
        try {
            Files.createDirectories(settingsFile.getParent());
            temporary = Files.createTempFile(settingsFile.getParent(), "seeing-settings-", ".tmp");
            Map<String, Object> content = new LinkedHashMap<>(measurement);
            content.put("preview", livePicture);
            mapper.writeValue(temporary.toFile(), content);
            try {
                Files.move(temporary, settingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
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

    private static void validatePreview(double gain, int frames) {
        if (!Double.isFinite(gain) || gain < MIN_PREVIEW_GAIN || gain > MAX_PREVIEW_GAIN
                || frames < 1 || frames > MAX_PREVIEW_FRAMES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Picture brightness must be 1–6 and averaged frames 1–" + MAX_PREVIEW_FRAMES);
        }
    }
}
