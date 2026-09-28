package edu.camserver.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Measured lens intrinsics only. LOST must recover the camera's attitude on every image.
 * Optional Brown-Conrady decentering terms act on radii normalised by 1000 pixels.
 */
record FisheyeLens(String cameraId, int width, int height, double centerX, double centerY,
                   double radial1, double radial3, double radial5, double aspectY, double maxAngleDeg,
                   Double detectionMaxAngleDeg, Double decentering1, Double decentering2) {
    private static final List<FisheyeLens> CALIBRATIONS = load();
    private static final double DECENTERING_SCALE_PX = 1000;

    static Optional<FisheyeLens> find(String cameraId, int width, int height) {
        return CALIBRATIONS.stream().filter(lens -> lens.cameraId.equals(cameraId)
                && lens.width == width && lens.height == height).findFirst();
    }

    private static List<FisheyeLens> load() {
        try (var input = new ClassPathResource("catalogs/fisheye-lenses.json").getInputStream()) {
            List<FisheyeLens> lenses = Arrays.asList(new ObjectMapper().readValue(input, FisheyeLens[].class));
            if (lenses.stream().anyMatch(lens -> !lens.valid())) {
                throw new IllegalStateException("Invalid bundled fisheye calibration");
            }
            return List.copyOf(lenses);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled fisheye calibrations", e);
        }
    }

    boolean valid() {
        if (cameraId == null || cameraId.isBlank() || width <= 0 || height <= 0
                || !Double.isFinite(centerX + centerY + radial1 + radial3 + radial5 + aspectY + maxAngleDeg)
                || centerX < 0 || centerX >= width || centerY < 0 || centerY >= height
                || aspectY < 0.5 || aspectY > 2 || maxAngleDeg <= 0 || maxAngleDeg >= 85) return false;
        if (detectionMaxAngleDeg != null && (!Double.isFinite(detectionMaxAngleDeg)
                || detectionMaxAngleDeg <= 0 || detectionMaxAngleDeg > maxAngleDeg)) return false;
        // Decentering is a sub-pixel correction; larger values would break the fixed-point inverse.
        if (!Double.isFinite(p1() + p2()) || Math.abs(p1()) > 0.005 || Math.abs(p2()) > 0.005) return false;
        for (int i = 0; i <= 100; i++) {
            double theta = Math.toRadians(maxAngleDeg) * i / 100;
            if (radial1 + 3 * radial3 * theta * theta + 5 * radial5 * Math.pow(theta, 4) <= 0) return false;
        }
        return true;
    }

    private double p1() {
        return decentering1 == null ? 0 : decentering1;
    }

    private double p2() {
        return decentering2 == null ? 0 : decentering2;
    }

    private double radius(double theta) {
        return theta * (radial1 + theta * theta * (radial3 + theta * theta * radial5));
    }

    double detectionRadiusPixels() {
        return radius(Math.toRadians(detectionMaxAngleDeg == null ? maxAngleDeg : detectionMaxAngleDeg));
    }

    /** Image offset (before the aspect factor) of an undistorted radial position. */
    private double[] decenter(double x, double y) {
        double xn = x / DECENTERING_SCALE_PX, yn = y / DECENTERING_SCALE_PX, r2 = xn * xn + yn * yn;
        return new double[]{
                x + DECENTERING_SCALE_PX * (2 * p1() * xn * yn + p2() * (r2 + 2 * xn * xn)),
                y + DECENTERING_SCALE_PX * (p1() * (r2 + 2 * yn * yn) + 2 * p2() * xn * yn)};
    }

    Optional<LostPlateSolver.Centroid> toPinhole(double x, double y, int size, double fieldWidthDeg) {
        if (!Double.isFinite(x + y) || x < 0 || y < 0 || x >= width || y >= height) return Optional.empty();
        double dx = x - centerX, dy = (y - centerY) / aspectY;
        // Remove decentering by fixed-point iteration; the correction is well below a pixel.
        double ux = dx, uy = dy;
        for (int i = 0; i < 8; i++) {
            double[] distorted = decenter(ux, uy);
            ux += dx - distorted[0];
            uy += dy - distorted[1];
        }
        double distance = Math.hypot(ux, uy);
        double high = Math.toRadians(maxAngleDeg), low = 0;
        if (distance > radius(high)) return Optional.empty();
        // Monotone calibrated polynomial: bisection cannot jump to an unphysical root.
        for (int i = 0; i < 45; i++) {
            double middle = (low + high) / 2;
            if (radius(middle) < distance) low = middle; else high = middle;
        }
        double center = (size - 1) / 2.0;
        double focal = center / Math.tan(Math.toRadians(fieldWidthDeg / 2));
        double scale = distance < 1e-9 ? focal / radial1 : focal * Math.tan((low + high) / 2) / distance;
        // Preserve the physical camera handedness; no azimuth or catalog coordinates enter this mapping.
        return Optional.of(new LostPlateSolver.Centroid(center + ux * scale, center + uy * scale));
    }

    /** Forward model, used by tests: pinhole point back to the measured image position. */
    Optional<double[]> fromPinhole(double px, double py, int size, double fieldWidthDeg) {
        double center = (size - 1) / 2.0;
        double focal = center / Math.tan(Math.toRadians(fieldWidthDeg / 2));
        double ox = px - center, oy = py - center, planar = Math.hypot(ox, oy);
        double theta = Math.atan(planar / focal);
        if (theta > Math.toRadians(maxAngleDeg)) return Optional.empty();
        double r = radius(theta);
        double ux = planar < 1e-12 ? 0 : ox / planar * r, uy = planar < 1e-12 ? 0 : oy / planar * r;
        double[] distorted = decenter(ux, uy);
        return Optional.of(new double[]{centerX + distorted[0], centerY + distorted[1] * aspectY});
    }
}
