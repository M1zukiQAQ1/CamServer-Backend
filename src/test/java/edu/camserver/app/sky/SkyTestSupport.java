package edu.camserver.app.sky;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.camserver.app.model.platesolve.PlateSolveResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;

final class SkyTestSupport {
    /** Capture time of frame 85123 (UCSB Broida roof), whose production plate solution is the fixture. */
    static final Instant CAPTURED_AT = Instant.parse("2026-11-09T11:31:01.823Z");
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private SkyTestSupport() {
    }

    static PlateSolveResult solvedFrame() {
        try (InputStream in = SkyTestSupport.class.getResourceAsStream("/sky/solve-85123.json")) {
            return MAPPER.readValue(in, PlateSolveResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static double separationDeg(double ra1, double dec1, double ra2, double dec2) {
        double[] a = SkyProjection.unit(Math.toRadians(ra1), Math.toRadians(dec1));
        double[] b = SkyProjection.unit(Math.toRadians(ra2), Math.toRadians(dec2));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, SkyProjection.dot(a, b)))));
    }
}
