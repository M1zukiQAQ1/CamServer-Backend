package edu.camserver.app.controller;

import edu.camserver.app.model.Camera;
import edu.camserver.app.model.Image;
import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.service.CameraService;
import edu.camserver.app.service.ImageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import java.net.MalformedURLException;
import java.nio.file.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://128.111.23.114")
@RestController
@RequestMapping("/api")
public class QueryController {

    public QueryController(ImagePaths imagePaths, ImageService imageService, CameraService cameraService) {
        this.imagePaths = imagePaths;
        this.imageService = imageService;
        this.cameraService = cameraService;
    }

    // Constructor injection
    ImageService imageService;
    CameraService cameraService;
    private final ImagePaths imagePaths;

    @GetMapping("/query")
    public List<Image> query(
            @RequestParam(defaultValue="20") int pagesize,
            @RequestParam(required=false) String conditions,
            @RequestParam(required=false) String lastUID) {

        return imageService.findAll(pagesize, lastUID, conditions);
    }

//    private final Path fileStorageLocation = Paths.get("/mnt/CamData/images/").toAbsolutePath().normalize();

    @GetMapping("/images/{fileName:.+}")
    public ResponseEntity<Resource> getFile(@PathVariable String fileName) {
        System.out.println(fileName);
        try {
            // Resolve file path safely
            Path filePath = imagePaths.resolve(fileName);
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            // Determine content type if needed
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            // Return file as downloadable response
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);

        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/sites")
    public List<Camera> sites() { return cameraService.getSites(); }

    @GetMapping("/feat")
    public ResponseEntity<Resource> feat() {
        return ResponseEntity.badRequest().build();
    }
}