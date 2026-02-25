//package edu.camserver.app.controller;
//
//import org.springframework.http.ResponseEntity;
//import org.springframework.stereotype.Controller;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//import jakarta.annotation.PostConstruct;
//import jakarta.servlet.ServletOutputStream;
//import jakarta.servlet.http.HttpServletResponse;
//import javax.imageio.ImageIO;
//
//import java.awt.image.BufferedImage;
//import java.io.ByteArrayInputStream;
//import java.util.concurrent.locks.ReentrantLock;
//
//@Controller
//public class LiveStreamController {
//
//    private volatile byte[] latestFrame = null;
//    private final ReentrantLock frameLock = new ReentrantLock();
//
//    // ------------------- upload_live -------------------
//    @PostMapping("/upload_live")
//    public ResponseEntity<?> uploadFrame(@RequestParam("image") MultipartFile file) {
//        try {
//            byte[] data = file.getBytes();
//            frameLock.lock();
//            latestFrame = data;
//        } catch (Exception e) {
//            return ResponseEntity.internalServerError().body("{\"ok\": false}");
//        } finally {
//            frameLock.unlock();
//        }
//        return ResponseEntity.ok("{\"ok\": true}");
//    }
//
//    // ------------------- seeing_monitor -------------------
//    @GetMapping(value = "/seeing_monitor", produces = "multipart/x-mixed-replace; boundary=frame")
//    public void streamVideo(HttpServletResponse response) {
//        response.setContentType("multipart/x-mixed-replace; boundary=frame");
//
//        try (ServletOutputStream out = response.getOutputStream()) {
//            while (true) {
//                byte[] frameData = null;
//                frameLock.lock();
//                try {
//                    if (latestFrame != null) {
//                        frameData = latestFrame;
//                    }
//                } finally {
//                    frameLock.unlock();
//                }
//
//                if (frameData != null) {
//                    // Optional: verify it's a valid image
//                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(frameData));
//                    if (img != null) {
//                        out.write(("--frame\r\nContent-Type: image/jpeg\r\n\r\n").getBytes());
//                        out.write(frameData);
//                        out.write("\r\n".getBytes());
//                        out.flush();
//                    }
//                }
//
//                // Control frame rate (optional)
//                Thread.sleep(50);  // ~20 FPS
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//}
//
