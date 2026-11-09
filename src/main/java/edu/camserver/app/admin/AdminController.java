package edu.camserver.app.admin;

import edu.camserver.app.service.FrameService;
import edu.camserver.app.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin panel API. {@link AdminInterceptor} has already checked the network and, except for
 * {@code login} and {@code session}, the session cookie before any method here runs.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    public record LoginRequest(String password) {
    }

    public record MeasurementRequest(Integer exposure, Integer gain, Boolean autoExposure) {
    }

    private final AdminAuthService auth;
    private final AnalyticsService analytics;
    private final AlertService alerts;
    private final SettingsService settings;
    private final FrameService frames;

    public AdminController(AdminAuthService auth, AnalyticsService analytics, AlertService alerts,
                           SettingsService settings, FrameService frames) {
        this.auth = auth;
        this.analytics = analytics;
        this.alerts = alerts;
        this.settings = settings;
        this.frames = frames;
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        AdminAuthService.Client client = client(request);
        Object session = request.getAttribute(AdminInterceptor.SESSION_ATTRIBUTE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", session != null);
        body.put("expiresAt", session instanceof AdminAuthService.Session s ? s.expiresAt() : null);
        body.put("clientAddress", client.address());
        body.put("network", client.network());
        body.put("passwordConfigured", auth.passwordConfigured());
        return body;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(HttpServletRequest request, @RequestBody LoginRequest login) {
        AdminAuthService.Login result = auth.login(client(request), login == null ? null : login.password());
        return switch (result.result()) {
            case OK -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookie(result.token(), auth.sessionLifetime()).toString())
                    .body(Map.of("ok", true, "expiresAt", result.session().expiresAt()));
            case WRONG_PASSWORD -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "error", "Wrong password."));
            case LOCKED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, result.retryAfter().toSeconds())))
                    .body(Map.of("ok", false, "error", "Too many failed logins. Try again in "
                            + Math.max(1, result.retryAfter().toMinutes()) + " minutes."));
            case NOT_CONFIGURED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "error", "No admin password is set on the server."));
        };
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        auth.logout(AdminInterceptor.token(request));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .body(Map.of("ok", true));
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics(@RequestParam(defaultValue = "30") int days) throws IOException {
        return analytics.summary(days);
    }

    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        return alerts.overview();
    }

    @PutMapping("/alerts")
    public Map<String, Object> updateAlerts(HttpServletRequest request, @RequestBody AlertSettings next) throws IOException {
        try {
            alerts.updateSettings(next);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        log.info("Alert settings changed by admin at {}", client(request).address());
        return alerts.overview();
    }

    /** Sends a test mail with the settings in the body (not saved), or the stored ones when the body is empty. */
    @PostMapping("/alerts/test")
    public ResponseEntity<Map<String, Object>> testAlert(@RequestBody(required = false) AlertSettings candidate) {
        String error = alerts.sendTest(candidate);
        return error == null
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("ok", false, "error", error));
    }

    @GetMapping("/measurement")
    public Map<String, Object> measurement() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settings", settings.getSettings());
        body.put("preview", settings.getPreview());
        body.put("telemetry", frames.telemetry());
        return body;
    }

    @PutMapping("/measurement")
    public Map<String, Object> updateMeasurement(HttpServletRequest request, @RequestBody MeasurementRequest next) {
        settings.update(next.exposure(), next.gain(), next.autoExposure());
        log.info("Seeing measurement settings set to {} by admin at {}", settings.getSettings(), client(request).address());
        return measurement();
    }

    private static AdminAuthService.Client client(HttpServletRequest request) {
        return (AdminAuthService.Client) request.getAttribute(AdminInterceptor.CLIENT_ATTRIBUTE);
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(AdminAuthService.COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Strict").path("/api/admin").maxAge(maxAge).build();
    }
}
