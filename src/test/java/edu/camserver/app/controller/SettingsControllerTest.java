package edu.camserver.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.camserver.app.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SettingsControllerTest {
    @TempDir Path dir;

    @Test void websiteCanSaveManualOverrideAndEnableAutomaticModeAgain() throws Exception {
        SettingsService settings = new SettingsService(new ObjectMapper(), dir.resolve("settings.json").toString(), 150000, 1);
        SettingsController controller = new SettingsController();
        ReflectionTestUtils.setField(controller, "settingsService", settings);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/settings").param("exposure", "2000").param("gain", "1").param("autoExposure", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.settings.autoExposure").value(false));
        mvc.perform(get("/settings")).andExpect(jsonPath("$.exposure").value(2000))
                .andExpect(jsonPath("$.autoExposure").value(false));
        mvc.perform(post("/settings").param("autoExposure", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.settings.exposure").value(2000))
                .andExpect(jsonPath("$.settings.autoExposure").value(true));
    }
}
