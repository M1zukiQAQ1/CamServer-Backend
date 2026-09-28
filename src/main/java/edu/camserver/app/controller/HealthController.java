package edu.camserver.app.controller;

import edu.camserver.app.service.HealthService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health of every camera feed and of the server itself, for the site's health panel.
 *
 * <p>{@code GET /api/health} returns the newest result of the background checks (see
 * {@code app.health.refresh-seconds}); a request never starts a check itself. The
 * {@code refresh} parameter sent by older pages is ignored. 503 only right after startup,
 * before the first check has finished.
 */
@RestController
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/api/health")
    public ResponseEntity<HealthService.HealthReport> report() {
        return healthService.report()
                .map(report -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.RETRY_AFTER, "5")
                        .cacheControl(CacheControl.noStore())
                        .build());
    }
}
