package edu.camserver.app.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Called by the agent on each camera host (Pi), not by browsers. Outside {@code /api/admin}
 * because the Pis sit on campus wired networks, not the VPN; each one authenticates with its own
 * token ({@code Authorization: Bearer <token>}) from {@code devices.json}.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final CameraControlService cameras;

    public DeviceController(CameraControlService cameras) {
        this.cameras = cameras;
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(HttpServletRequest request,
                                                         @RequestHeader(name = "Authorization", required = false) String authorization,
                                                         @RequestBody(required = false) Map<String, Object> report) {
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7) : null;
        Optional<String> device = cameras.authenticate(token);
        if (device.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "error", "Unknown device token."));
        }
        String address = Optional.ofNullable(request.getHeader("X-Real-IP")).orElse(request.getRemoteAddr());
        List<Map<String, Object>> commands = cameras.heartbeat(device.get(), report, address);
        return ResponseEntity.ok(Map.of("ok", true, "device", device.get(), "commands", commands));
    }
}
