package edu.camserver.app.controller;

import edu.camserver.app.service.DatabaseService;
import edu.camserver.app.config.ImagePaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final ImagePaths imagePaths;

    public UploadController(ImagePaths imagePaths) {
        this.imagePaths = imagePaths;
    }

    @Autowired
    private DatabaseService databaseService;

    @PostMapping("/upload_image")
    public ResponseEntity<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam Map<String, String> spec) {

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
                databaseService.insertImage(imgPath, spec);
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
