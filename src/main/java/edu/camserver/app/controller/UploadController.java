package edu.camserver.app.controller;

import edu.camserver.app.model.Image;
import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.service.ImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final ImagePaths imagePaths;
    private final ImageService imageService;

    public UploadController(ImagePaths imagePaths, ImageService imageService) {
        this.imagePaths = imagePaths;
        this.imageService = imageService;
    }

    @PostMapping("/upload_image")
    public ResponseEntity<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("camId") String camId,
            @RequestParam("siteName") String siteName,
            @RequestParam("date") String date,
            @RequestParam("bit") int bit,
            @RequestParam("gain") int gain,
            @RequestParam("exposure") int exposure,
            @RequestParam("temperature") float temperature,
            @RequestParam("humidity") float humidity,
            @RequestParam("timeZone") String timeZone
            ) {

        try {
            // Save file
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing filename");
            }

            File dest = imagePaths.fileFor(filename);
            file.transferTo(dest);

            // Insert into database if .jpg
            if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
                String imgPath = dest.getAbsolutePath().replace(".jpg", "").replace(".jpeg", "");
                imageService.save(new Image(camId, siteName, LocalDateTime.parse(date), bit, gain, exposure, imgPath, temperature, humidity, timeZone));
            }

            // Return JSON success
            return ResponseEntity.status(201)
                    .body(Map.of("message", "File " + filename + " uploaded"));

        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Database insert failed: " + e.getMessage()));
        }
    }
}
