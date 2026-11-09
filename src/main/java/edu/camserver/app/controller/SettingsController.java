package edu.camserver.app.controller;

import edu.camserver.app.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public seeing-monitor settings ({@code /settings}; nginx also serves it as {@code /api/settings}).
 *
 * <p>Anyone may change the live picture ({@code previewGain}, {@code previewFrames}). The camera's
 * exposure, gain and automatic exposure decide whether the unattended measurement works, so they
 * are only accepted from the admin panel ({@code PUT /api/admin/measurement}); a public request
 * that tries to set them is refused as a whole.
 */
@RestController
public class SettingsController {
    @Autowired private SettingsService settingsService;

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return current();
    }

    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestParam(required = false) Integer exposure,
                                                              @RequestParam(required = false) Integer gain,
                                                              @RequestParam(required = false) Boolean autoExposure,
                                                              @RequestParam(required = false) Double previewGain,
                                                              @RequestParam(required = false) Integer previewFrames) {
        if (exposure != null || gain != null || autoExposure != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ok", false,
                    "error", "Exposure, gain and automatic exposure drive the seeing measurement and are set by "
                            + "administrators. This form only changes the live picture.",
                    "settings", current()));
        }
        settingsService.updatePreview(previewGain, previewFrames);
        return ResponseEntity.ok(Map.of("ok", true, "settings", current()));
    }

    /** Measurement settings at the top level (the shape older pages and producers read) plus the preview. */
    private Map<String, Object> current() {
        Map<String, Object> body = new LinkedHashMap<>(settingsService.getSettings());
        body.put("preview", settingsService.getPreview());
        return body;
    }
}
