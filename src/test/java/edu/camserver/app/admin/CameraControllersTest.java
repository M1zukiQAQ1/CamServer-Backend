package edu.camserver.app.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CameraControllersTest {
    @TempDir Path dir;

    private MockMvc mvc;

    @BeforeEach void setUp() throws Exception {
        AdminProperties properties = new AdminProperties();
        properties.setAllowedNetworks(List.of("128.111.180.0/22"));
        Path password = dir.resolve("password");
        Files.writeString(password, PasswordHash.encode("s3cret-Admin-pass".toCharArray(), new byte[16], 100_000));
        properties.setPasswordFile(password.toString());
        AdminAuthService auth = new AdminAuthService(properties);
        Files.writeString(dir.resolve("devices.json"), """
                {"devices": [{"id": "allskycam2", "name": "Broida all-sky", "tokenSha256": "%s"}]}
                """.formatted(CameraControlService.sha256("pi-token")));
        CameraControlService cameras = new CameraControlService(dir.resolve("devices.json"), Clock.systemUTC());
        AdminController admin = new AdminController(auth, null, null, null, null);
        mvc = MockMvcBuilders.standaloneSetup(admin, new CameraAdminController(cameras), new DeviceController(cameras))
                .addMappedInterceptors(new String[] {"/api/admin", "/api/admin/**"}, new AdminInterceptor(auth, new ObjectMapper()))
                .build();
    }

    private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
        return request.with(r -> { r.setRemoteAddr("127.0.0.1"); return r; }).header("X-Real-IP", address);
    }

    private Cookie login() throws Exception {
        return mvc.perform(from("128.111.180.9", post("/api/admin/login")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"s3cret-Admin-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(AdminAuthService.COOKIE);
    }

    private void heartbeat(String body) throws Exception {
        mvc.perform(from("128.111.23.98", post("/api/devices/heartbeat")).header("Authorization", "Bearer pi-token")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test void devicesNeedTheirTokenButNotTheVpn() throws Exception {
        mvc.perform(from("128.111.23.98", post("/api/devices/heartbeat")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(from("128.111.23.98", post("/api/devices/heartbeat")).header("Authorization", "Bearer nope")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        heartbeat("{\"capabilities\":[\"CAPTURE\"]}");
    }

    @Test void adminQueuesAndTheDevicePicksUp() throws Exception {
        heartbeat("{\"capabilities\":[\"CAPTURE\",\"REBOOT\"],\"sensors\":{\"temperature\":20.5,\"humidity\":41}}");
        mvc.perform(from("128.111.23.98", get("/api/admin/cameras"))).andExpect(status().isForbidden());
        mvc.perform(from("128.111.180.9", get("/api/admin/cameras"))).andExpect(status().isUnauthorized());
        Cookie cookie = login();
        mvc.perform(from("128.111.180.9", get("/api/admin/cameras")).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].online").value(true))
                .andExpect(jsonPath("$.devices[0].sourceAddress").value("128.111.23.98"))
                .andExpect(jsonPath("$.devices[0].report.sensors.temperature").value(20.5));

        mvc.perform(from("128.111.180.9", post("/api/admin/cameras/allskycam2/commands")).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"REBOOT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").exists());
        mvc.perform(from("128.111.180.9", post("/api/admin/cameras/allskycam2/commands")).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FORMAT_DISK\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(from("128.111.180.9", post("/api/admin/cameras/allskycam2/commands")).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CAPTURE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.issuedBy").value("128.111.180.9"));

        mvc.perform(from("128.111.23.98", post("/api/devices/heartbeat")).header("Authorization", "Bearer pi-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands", hasSize(1)))
                .andExpect(jsonPath("$.commands[0].type").value("CAPTURE"));
    }

    @Test void heaterSettingsTravelToTheDevice() throws Exception {
        heartbeat("{\"capabilities\":[\"HEATER\"],\"heater\":{\"mode\":\"OFF\",\"on\":false}}");
        Cookie cookie = login();
        mvc.perform(from("128.111.180.9", post("/api/admin/cameras/allskycam2/commands")).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HEATER\",\"mode\":\"HOT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").exists());
        mvc.perform(from("128.111.180.9", post("/api/admin/cameras/allskycam2/commands")).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"HEATER\",\"mode\":\"AUTO\",\"target\":15}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.params.mode").value("AUTO"));
        mvc.perform(from("128.111.180.9", get("/api/admin/cameras")).cookie(cookie))
                .andExpect(jsonPath("$.devices[0].report.heater.mode").value("OFF"));
        mvc.perform(from("128.111.23.98", post("/api/devices/heartbeat")).header("Authorization", "Bearer pi-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.commands[0].type").value("HEATER"))
                .andExpect(jsonPath("$.commands[0].mode").value("AUTO"))
                .andExpect(jsonPath("$.commands[0].target").value(15.0));
    }
}
