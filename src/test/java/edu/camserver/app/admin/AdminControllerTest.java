package edu.camserver.app.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.camserver.app.service.FrameService;
import edu.camserver.app.service.SettingsService;
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
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminControllerTest {
    @TempDir Path dir;

    private MockMvc mvc;
    private SettingsService settings;
    private AnalyticsService analytics;

    @BeforeEach void setUp() throws Exception {
        AdminProperties properties = new AdminProperties();
        properties.setAllowedNetworks(List.of("128.111.180.0/22"));
        Path password = dir.resolve("password");
        Files.writeString(password, PasswordHash.encode("s3cret-Admin-pass".toCharArray(), new byte[16], 100_000));
        properties.setPasswordFile(password.toString());
        AdminAuthService auth = new AdminAuthService(properties);
        settings = new SettingsService(new ObjectMapper(), dir.resolve("settings.json").toString(), 100000, 60);
        analytics = mock(AnalyticsService.class);
        when(analytics.summary(anyInt())).thenReturn(Map.of("views", 3L));
        FrameService frames = mock(FrameService.class);
        when(frames.telemetry()).thenReturn(Map.of());
        AdminController controller = new AdminController(auth, analytics, mock(AlertService.class), settings, frames);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addMappedInterceptors(new String[] {"/api/admin", "/api/admin/**"}, new AdminInterceptor(auth, new ObjectMapper()))
                .build();
    }

    /** As nginx forwards it: over loopback with the client's address in X-Real-IP. */
    private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
        return request.with(r -> { r.setRemoteAddr("127.0.0.1"); return r; }).header("X-Real-IP", address);
    }

    private Cookie login() throws Exception {
        Cookie cookie = mvc.perform(from("128.111.180.9", post("/api/admin/login")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"s3cret-Admin-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andReturn().getResponse().getCookie(AdminAuthService.COOKIE);
        assertNotNull(cookie);
        return cookie;
    }

    @Test void outsideTheVpnEverythingIsRefusedEvenWithASession() throws Exception {
        Cookie cookie = login();
        mvc.perform(from("203.0.113.7", get("/api/admin/analytics")).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(from("203.0.113.7", get("/api/admin/session"))).andExpect(status().isForbidden());
        mvc.perform(from("203.0.113.7", post("/api/admin/login")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"s3cret-Admin-pass\"}")).andExpect(status().isForbidden());
        verify(analytics, never()).summary(anyInt());
    }

    @Test void onTheVpnTheSessionCookieIsRequired() throws Exception {
        mvc.perform(from("128.111.180.9", get("/api/admin/session")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.network").value("128.111.180.0/22"));
        mvc.perform(from("128.111.180.9", get("/api/admin/analytics"))).andExpect(status().isUnauthorized());
        mvc.perform(from("128.111.180.9", post("/api/admin/login")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"nope\"}")).andExpect(status().isUnauthorized());
        Cookie cookie = login();
        mvc.perform(from("128.111.180.9", get("/api/admin/analytics")).cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.views").value(3));
        mvc.perform(from("128.111.180.9", post("/api/admin/logout")).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(from("128.111.180.9", get("/api/admin/analytics")).cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test void writesMustBeJson() throws Exception {
        Cookie cookie = login();
        mvc.perform(from("128.111.180.9", put("/api/admin/measurement")).cookie(cookie)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("exposure=10000"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test void adminsSetTheMeasurement() throws Exception {
        Cookie cookie = login();
        mvc.perform(from("128.111.180.9", put("/api/admin/measurement")).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exposure\":10000,\"gain\":50,\"autoExposure\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.settings.exposure").value(10000))
                .andExpect(jsonPath("$.settings.autoExposure").value(true));
        assertEquals(Map.of("exposure", 10000, "gain", 50, "autoExposure", true), settings.getSettings());
    }
}
