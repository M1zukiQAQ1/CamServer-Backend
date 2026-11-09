package edu.camserver.app.sky;

import jakarta.persistence.NoResultException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sky quality and the constellation/planet overlay of a plate-solved frame, and trails in any night frame. Read-only. */
@RestController
@RequestMapping("/api/sky")
public class SkyController {
    private final SkyService skyService;
    private final StreakService streakService;

    public SkyController(SkyService skyService, StreakService streakService) {
        this.skyService = skyService;
        this.streakService = streakService;
    }

    @GetMapping("/{imgId}")
    public SkyView view(@PathVariable long imgId) {
        return skyService.view(imgId);
    }

    @GetMapping("/{imgId}/streaks")
    public StreakService.StreakView streaks(@PathVariable long imgId) {
        return streakService.view(imgId);
    }

    @ExceptionHandler(NoResultException.class)
    public ResponseEntity<Map<String, Object>> unknownImage() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "error", "Image not found."));
    }
}
