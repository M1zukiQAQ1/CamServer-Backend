package edu.camserver.app.controller;

import edu.camserver.app.service.NightlyService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Nightly keograms and time-lapse videos. Reading and asking for a missing video are public; rebuilding a night is an admin action. */
@RestController
public class NightlyController {
    private final NightlyService nightly;

    public NightlyController(NightlyService nightly) {
        this.nightly = nightly;
    }

    @GetMapping("/api/nightly")
    public List<NightlyService.Night> list(@RequestParam(required = false) String cameraId,
                                           @RequestParam(defaultValue = "30") int limit) {
        return nightly.list(cameraId == null || cameraId.isBlank() ? null : cameraId.trim(), limit);
    }

    /**
     * {@code keogram.jpg} or {@code timelapse.mp4}. Spring answers {@code Range} requests on a
     * file resource with 206 and the requested bytes, which is what lets browsers seek in the video.
     */
    @GetMapping("/api/nightly/{cameraId}/{date}/{file:.+}")
    public ResponseEntity<Resource> file(@PathVariable String cameraId, @PathVariable String date, @PathVariable String file) {
        Optional<Path> path = nightly.file(cameraId, date, file);
        if (path.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = file.endsWith(".mp4") ? MediaType.parseMediaType("video/mp4") : MediaType.IMAGE_JPEG;
        // A night can be rebuilt, so let browsers revalidate after a short while.
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
                .body(new FileSystemResource(path.get()));
    }

    /** Where the video of one night stands: ready, queued, building, none or unavailable. */
    @GetMapping("/api/nightly/{cameraId}/{date}")
    public ResponseEntity<?> status(@PathVariable String cameraId, @PathVariable String date) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(nightly.status(cameraId, LocalDate.parse(date)));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "The date must look like 2026-11-08."));
        }
    }

    /**
     * Asks for the video of one completed night; open to everyone. Answers at once with the state
     * (the video is made in the background, one night at a time); poll the GET above until "ready".
     */
    @PostMapping("/api/nightly/{cameraId}/{date}")
    public ResponseEntity<?> request(@PathVariable String cameraId, @PathVariable String date) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(nightly.request(cameraId, LocalDate.parse(date), java.time.Instant.now()));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "The date must look like 2026-11-08."));
        }
    }

    /** Builds or rebuilds one night. Under /api/admin, so the admin interceptor has checked VPN and session. */
    @PostMapping("/api/admin/nightly/{cameraId}/{date}")
    public ResponseEntity<?> rebuild(@PathVariable String cameraId, @PathVariable String date) throws IOException {
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "The date must look like 2026-11-08."));
        }
        Optional<NightlyService.Night> night = nightly.build(cameraId, day);
        return night.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("ok", false, "error", "Unknown camera, or too few frames that night.")));
    }
}
