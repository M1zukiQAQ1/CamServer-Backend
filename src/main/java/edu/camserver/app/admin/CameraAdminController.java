package edu.camserver.app.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Camera hosts in the admin panel: status, sensors, and commands (behind {@link AdminInterceptor}). */
@RestController
@RequestMapping("/api/admin/cameras")
public class CameraAdminController {

    private static final Logger log = LoggerFactory.getLogger(CameraAdminController.class);

    /** {@code mode} and {@code target} (°C) are for HEATER only. */
    public record CommandRequest(String type, String confirm, String mode, Double target) {
    }

    private final CameraControlService cameras;

    public CameraAdminController(CameraControlService cameras) {
        this.cameras = cameras;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("devices", cameras.overview());
    }

    @PostMapping("/{id}/commands")
    public ResponseEntity<Map<String, Object>> command(HttpServletRequest request, @PathVariable String id,
                                                       @RequestBody CommandRequest body) {
        CameraControlService.CommandType type;
        try {
            type = CameraControlService.CommandType.valueOf(body == null || body.type() == null ? "" : body.type());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "Unknown command; use one of "
                    + List.of(CameraControlService.CommandType.values()) + ".");
        }
        String admin = ((AdminAuthService.Client) request.getAttribute(AdminInterceptor.CLIENT_ATTRIBUTE)).address();
        CameraControlService.Command command;
        try {
            Map<String, Object> params = new HashMap<>();
            if (body.mode() != null) params.put("mode", body.mode());
            if (body.target() != null) params.put("target", body.target());
            command = cameras.queue(id, type, body.confirm(), admin, params);
        } catch (CameraControlService.CommandRejected e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
        log.info("{} queued for camera host {} by admin at {} ({})", type, id, admin, command.id);
        return ResponseEntity.ok(command.toView());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", message));
    }
}
