package edu.camserver.app.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * {@code POST /api/analytics/view}: the site reports a page view ({@code navigator.sendBeacon},
 * JSON {@code {"path": "/gallery", "referrer": "..."}}, sent as JSON or text/plain). Always 204
 * so the page never retries.
 */
@RestController
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final AdminAuthService auth;
    private final ObjectMapper mapper;

    public AnalyticsController(AnalyticsService analytics, AdminAuthService auth, ObjectMapper mapper) {
        this.analytics = analytics;
        this.auth = auth;
        this.mapper = mapper;
    }

    @PostMapping("/api/analytics/view")
    public ResponseEntity<Void> view(HttpServletRequest request, @RequestBody(required = false) String body) {
        if (body != null && body.length() <= 4096) {
            try {
                JsonNode view = mapper.readTree(body);
                if (view != null && view.path("path").isTextual()) {
                    analytics.record(auth.client(request).address(), request.getHeader(HttpHeaders.USER_AGENT),
                            view.path("path").asText(), view.path("referrer").isTextual() ? view.path("referrer").asText() : null);
                }
            } catch (IOException ignored) {
                // Not JSON: nothing to count.
            }
        }
        return ResponseEntity.noContent().build();
    }
}
