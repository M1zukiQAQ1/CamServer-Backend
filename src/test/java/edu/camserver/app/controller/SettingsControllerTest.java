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

    private MockMvc mvc(SettingsService settings) {
        SettingsController controller = new SettingsController();
        ReflectionTestUtils.setField(controller, "settingsService", settings);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void websiteChangesOnlyTheLivePicture() throws Exception {
        SettingsService settings = new SettingsService(new ObjectMapper(), dir.resolve("settings.json").toString(), 150000, 1);
        settings.update(10000, 50, true);
        MockMvc mvc = mvc(settings);
        mvc.perform(post("/settings").param("previewGain", "4.5").param("previewFrames", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.preview.gain").value(4.5))
                .andExpect(jsonPath("$.settings.preview.frames").value(4))
                .andExpect(jsonPath("$.settings.exposure").value(10000));
        mvc.perform(get("/settings")).andExpect(jsonPath("$.exposure").value(10000))
                .andExpect(jsonPath("$.autoExposure").value(true))
                .andExpect(jsonPath("$.preview.frames").value(4));
    }

    @Test void publicRequestsCannotChangeTheMeasurement() throws Exception {
        SettingsService settings = new SettingsService(new ObjectMapper(), dir.resolve("settings.json").toString(), 150000, 1);
        settings.update(10000, 50, true);
        MockMvc mvc = mvc(settings);
        mvc.perform(post("/settings").param("exposure", "1000").param("gain", "50").param("autoExposure", "false"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.settings.exposure").value(10000));
        // Mixed requests are refused whole: the picture part is not applied either.
        mvc.perform(post("/settings").param("autoExposure", "false").param("previewGain", "2"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/settings")).andExpect(jsonPath("$.exposure").value(10000))
                .andExpect(jsonPath("$.autoExposure").value(true))
                .andExpect(jsonPath("$.preview.gain").value(3.0));
    }
}
