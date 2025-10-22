package edu.camserver.app.controller;

import edu.camserver.app.model.Settings;
import org.springframework.web.bind.annotation.*;

@RestController
public class MonitorController {

    private Settings currentSettings = new Settings(10000, 100);

    @GetMapping("/settings")
    public Settings getSettings() {
        return currentSettings;
    }

    @PostMapping("/settings")
    public Settings updateSettings(@RequestParam(required=false) Integer exposure,
                                   @RequestParam(required=false) Integer gain) {
        if (exposure != null) currentSettings.setExposure(exposure);
        if (gain != null) currentSettings.setGain(gain);
        return currentSettings;
    }



}