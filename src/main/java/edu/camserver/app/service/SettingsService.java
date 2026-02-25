package edu.camserver.app.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SettingsService {
    private final Map<String, Integer> settings = new ConcurrentHashMap<>(Map.of(
            "exposure", 1000,
            "gain", 1
    ));

    public Map<String, Integer> getSettings() { return settings; }

    public void update(Integer exposure, Integer gain) {
        if (exposure != null) settings.put("exposure", exposure);
        if (gain != null) settings.put("gain", gain);
    }
}

