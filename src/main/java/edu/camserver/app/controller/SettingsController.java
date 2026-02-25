package edu.camserver.app.controller;

import edu.camserver.app.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
@RestController
public class SettingsController {
    @Autowired private SettingsService settingsService;

    @GetMapping("/settings")
    public Map<String, Integer> getSettings() {
        return settingsService.getSettings();
    }

    @PostMapping("/settings")
    public Map<String, Object> updateSettings(@RequestParam(required = false) Integer exposure,
                                              @RequestParam(required = false) Integer gain) {
        settingsService.update(exposure, gain);
        return Map.of("ok", true, "settings", settingsService.getSettings());
    }
}
