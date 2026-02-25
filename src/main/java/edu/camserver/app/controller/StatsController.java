package edu.camserver.app.controller;

import edu.camserver.app.service.FrameService;
import edu.camserver.app.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StatsController {
    @Autowired
    private FrameService frameService;
    @Autowired private SettingsService settingsService;

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("meta", frameService.getLatestMeta(),
                "settings", settingsService.getSettings());
    }

    @GetMapping("/live_pos")
    public String livePos() {
        return frameService.getPosMeta();
    }
}

