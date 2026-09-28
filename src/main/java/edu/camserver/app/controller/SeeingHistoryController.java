package edu.camserver.app.controller;

import edu.camserver.app.service.SeeingHistoryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

@RestController
public class SeeingHistoryController {
    private final SeeingHistoryService history;
    public SeeingHistoryController(SeeingHistoryService history) { this.history = history; }

    @GetMapping("/api/live/seeing/history")
    public Map<String, Object> history(@RequestParam String start, @RequestParam String end,
            @RequestParam(defaultValue = "1200") int maxPoints) {
        try { return history.query(parse(start), parse(end), maxPoints); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Seeing history storage is unavailable", e); }
    }

    @GetMapping("/api/live/seeing/history.csv")
    public void csv(@RequestParam String start, @RequestParam String end, HttpServletResponse response) throws IOException {
        Instant from = parse(start), to = parse(end);
        try { SeeingHistoryService.validateRange(from, to); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=seeing-history.csv");
        response.setHeader("Cache-Control", "no-store");
        history.exportCsv(from, to, response.getWriter());
    }

    private static Instant parse(String value) {
        try { return Instant.parse(value); }
        catch (DateTimeParseException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start and end must be ISO timestamps including timezone"); }
    }
}
