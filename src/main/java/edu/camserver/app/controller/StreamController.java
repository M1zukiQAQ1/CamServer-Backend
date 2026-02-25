package edu.camserver.app.controller;

import edu.camserver.app.service.FrameService;
import edu.camserver.app.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

@RestController
public class StreamController {

    @Autowired private FrameService frameService;
    @Autowired private SettingsService settingsService;

    @PostMapping("/upload_live")
    public ResponseEntity<Map<String, Object>> uploadFrame(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "ts", required = false) String tsStr,
            @RequestParam(value = "pos", required = false) String pos) {

        try {
            byte[] data = image.getBytes();
            double serverTs = System.currentTimeMillis() / 1000.0; // seconds epoch

            // Parse "%S.%f" client timestamp (e.g. "07.123456")
            ParsedTime parsed = parseClientSecondsFrac(tsStr, serverTs);

            frameService.updateFrame(
                    data,
                    parsed.clientEpoch,   // client_ts (full epoch)
                    serverTs,
                    parsed.latencyMs
            );

            frameService.updatePos(pos);

            Map<String, Object> resp = new HashMap<>();
            resp.put("ok", true);
            resp.put("settings", settingsService.getSettings());
            resp.put("latency_ms", parsed.latencyMs);
            return ResponseEntity.ok(resp);

        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /**
     * Parse a '%S.%f' string (0–59.999999) into full epoch seconds.
     * It “snaps” to the nearest minute around the current server timestamp.
     */
    private ParsedTime parseClientSecondsFrac(String ssf, double serverTs) {
        if (ssf == null || ssf.isEmpty()) {
            return new ParsedTime(serverTs, 0);
        }

        double s;
        try {
            s = Double.parseDouble(ssf) % 60.0;  // handle wrap-around or malformed values
        } catch (NumberFormatException e) {
            return new ParsedTime(serverTs, 0);
        }

        // Start of the current minute on the server clock
        double minuteFloor = serverTs - (serverTs % 60.0);

        // Candidates: previous, current, next minute
        double[] candidates = {
                minuteFloor - 60.0 + s,
                minuteFloor + s,
                minuteFloor + 60.0 + s
        };

        // Choose the one closest to the server timestamp
        double clientEpoch = Arrays.stream(candidates)
                .boxed()
                .min(Comparator.comparingDouble(c -> Math.abs(c - serverTs)))
                .orElse(serverTs);

        double delta = serverTs - clientEpoch;
        int latencyMs = (int) Math.round(Math.max(0.0, delta) * 1000.0);

        return new ParsedTime(clientEpoch, latencyMs);
    }

    private static class ParsedTime {
        final double clientEpoch;
        final int latencyMs;
        ParsedTime(double clientEpoch, int latencyMs) {
            this.clientEpoch = clientEpoch;
            this.latencyMs = latencyMs;
        }
    }

    @GetMapping(value = "/seeing_monitor", produces = "multipart/x-mixed-replace;boundary=frame")
    public ResponseEntity<StreamingResponseBody> streamVideo() {
        StreamingResponseBody body = outputStream -> {
            while (true) {
                byte[] frame = frameService.getLatestFrame();
                if (frame != null) {
                    outputStream.write(("--frame\r\nContent-Type: image/jpeg\r\n\r\n").getBytes());
                    outputStream.write(frame);
                    outputStream.write("\r\n".getBytes());
                    outputStream.flush();
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.asMediaType(MediaType.parseMediaType(
                        "multipart/x-mixed-replace;boundary=frame")))
                .body(body);
    }
}


