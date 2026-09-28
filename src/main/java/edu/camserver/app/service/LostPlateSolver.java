package edu.camserver.app.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Adapter for UWCubeSat/LOST's Pyramid star identification and Davenport Q method. */
@Service
public class LostPlateSolver {
    private final boolean enabled;
    private final String command;
    private final Path database;
    private final int timeoutSeconds;
    private final double angularToleranceDeg;
    private final int maxStars;

    public LostPlateSolver(
            @Value("${app.plate-solve.lost.enabled:true}") boolean enabled,
            @Value("${app.plate-solve.lost.command:lost}") String command,
            @Value("${app.plate-solve.lost.database:data/lost/bright-stars.dat}") String database,
            @Value("${app.plate-solve.lost.timeout-seconds:15}") int timeoutSeconds,
            @Value("${app.plate-solve.lost.angular-tolerance-deg:0.05}") double angularToleranceDeg,
            @Value("${app.plate-solve.lost.max-stars:40}") int maxStars) {
        this.enabled = enabled;
        this.command = command;
        this.database = Path.of(database).toAbsolutePath();
        this.timeoutSeconds = Math.max(1, Math.min(120, timeoutSeconds));
        this.angularToleranceDeg = Double.isFinite(angularToleranceDeg)
                ? Math.max(0.001, Math.min(0.1, angularToleranceDeg)) : 0.05;
        this.maxStars = Math.max(6, Math.min(80, maxStars));
    }

    boolean isAvailable() {
        if (!enabled || command == null || command.isBlank() || !Files.isReadable(database)
                || !Files.isReadable(database.resolveSibling("bright-star-catalog.tsv"))) {
            return false;
        }
        if (command.contains("/")) {
            return Files.isExecutable(Path.of(command));
        }
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(directory, command))) {
                return true;
            }
        }
        return false;
    }

    Run solve(Path workspace, List<Centroid> centroids, int width, int height, double fieldWidthDeg)
            throws InterruptedException {
        return solveWithDeadline(workspace, centroids, width, height, fieldWidthDeg, timeoutSeconds);
    }

    private Run solveWithDeadline(Path workspace, List<Centroid> centroids, int width, int height,
                                  double fieldWidthDeg, int deadlineSeconds) throws InterruptedException {
        if (!isAvailable() || centroids.size() < 6) {
            return new Run(null, "LOST unavailable or fewer than six usable detections.");
        }
        Process process = null;
        try {
            Files.createDirectories(workspace);
            // Every invocation gets fresh outputs: a failed forced solve must never reuse an old attitude.
            Path runDir = Files.createTempDirectory(workspace, "lost-").toAbsolutePath();
            Path input = runDir.resolve("centroids.png");
            Path attitude = runDir.resolve("attitude.txt");
            Path log = runDir.resolve("lost.log");
            writeCentroids(input, centroids.stream().limit(maxStars).toList(), width, height);
            // Our pixel centers run from 0 to width-1. LOST uses 0.5 to width-0.5.
            double focalPixels = (width - 1) / (2 * Math.tan(Math.toRadians(fieldWidthDeg / 2)));
            List<String> args = List.of(command, "pipeline", "--png", input.toString(),
                    "--focal-length", Double.toString(focalPixels / 1000), "--pixel-size", "1",
                    "--database", database.toString(), "--centroid-algo", "cog",
                    "--star-id-algo", "py", "--angular-tolerance", Double.toString(angularToleranceDeg),
                    "--false-stars-estimate", "1000", "--max-mismatch-probability", "0.0001",
                    "--attitude-algo", "dqm", "--print-attitude", attitude.toString());
            ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("LOST_BSC_PATH", database.resolveSibling("bright-star-catalog.tsv").toString());
            process = builder.start();
            if (!process.waitFor(deadlineSeconds, TimeUnit.SECONDS)) {
                return new Run(null, "LOST timed out after " + deadlineSeconds + " seconds; see " + log);
            }
            String detail = readTail(log);
            if (process.exitValue() != 0 || !Files.isRegularFile(attitude)) {
                return new Run(null, "LOST did not return an attitude.\n" + detail);
            }
            return new Run(parseAttitude(Files.readString(attitude)).orElse(null), detail);
        } catch (IOException | IllegalArgumentException e) {
            return new Run(null, "LOST failed: " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                // LOST is launched directly and does not spawn subprocesses.
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        }
    }

    Run solveVerified(Path workspace, List<Centroid> centroids, int width, int height, double fieldWidthDeg,
                      Predicate<Attitude> verify) throws InterruptedException {
        StringBuilder history = new StringBuilder();
        int previous = -1;
        // Pyramid stops at its first unique four-star candidate. A contaminated detection can make
        // that candidate wrong. Retry progressively cleaner measured cohorts, verifying each one.
        for (int limit : new int[]{maxStars, Math.max(6, maxStars * 3 / 4), Math.max(6, maxStars / 2)}) {
            int count = Math.min(limit, centroids.size());
            if (count == previous) continue;
            previous = count;
            Run run = solve(workspace, centroids.stream().limit(count).toList(), width, height, fieldWidthDeg);
            if (run.attitude() == null) return new Run(null, history + run.log());
            if (verify.test(run.attitude())) return new Run(run.attitude(), history + run.log());
            history.append("Rejected LOST candidate from ").append(count)
                    .append(" measured detections: independent catalog/zenith verification failed.\n");
        }
        return new Run(null, history.toString());
    }

    private String readTail(Path path) throws IOException {
        try (var file = new java.io.RandomAccessFile(path.toFile(), "r")) {
            file.seek(Math.max(0, file.length() - 8192));
            byte[] bytes = new byte[(int) Math.min(8192, file.length())];
            file.readFully(bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Render only measured, masked and undistorted centroids; never projected catalog stars. */
    static void writeCentroids(Path path, List<Centroid> stars, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (Centroid star : stars) {
            if (!Double.isFinite(star.x()) || !Double.isFinite(star.y())
                    || star.x() < 5 || star.y() < 5 || star.x() >= width - 5 || star.y() >= height - 5) {
                continue;
            }
            // A small Gaussian retains fractional pixels through LOST's center-of-gravity extractor.
            for (int y = (int) star.y() - 4; y <= (int) star.y() + 4; y++) {
                for (int x = (int) star.x() - 4; x <= (int) star.x() + 4; x++) {
                    double radiusSq = Math.pow(x - star.x(), 2) + Math.pow(y - star.y(), 2);
                    int value = (int) Math.round(240 * Math.exp(-radiusSq / 2));
                    value = Math.max(value, image.getRGB(x, y) & 255);
                    image.setRGB(x, y, value * 0x010101);
                }
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }

    static Optional<Attitude> parseAttitude(String text) {
        Map<String, Double> values = new HashMap<>();
        try {
            for (String line : text.split("\\R")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].startsWith("attitude_")) {
                    double value = Double.parseDouble(parts[1]);
                    if (!Double.isFinite(value) || values.putIfAbsent(parts[0], value) != null) {
                        return Optional.empty();
                    }
                }
            }
            if (!Double.valueOf(1).equals(values.get("attitude_known"))) {
                return Optional.empty();
            }
            double w = values.get("attitude_real");
            double x = values.get("attitude_i");
            double y = values.get("attitude_j");
            double z = values.get("attitude_k");
            double norm = Math.sqrt(w * w + x * x + y * y + z * z);
            // LOST prints rounded coefficients; normalize only a quaternion already close to unit length.
            if (Math.abs(norm - 1) > 0.001) {
                return Optional.empty();
            }
            return Optional.of(new Attitude(w / norm, x / norm, y / norm, z / norm));
        } catch (NullPointerException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    record Centroid(double x, double y) { }
    record Run(Attitude attitude, String log) { }
    record Coordinate(double raDeg, double decDeg) { }

    record Attitude(double w, double x, double y, double z) {
        Coordinate pixelToSky(double pixelX, double pixelY, int width, int height, double fieldWidthDeg) {
            double[] sky = toSky(cameraRay(pixelX, pixelY, width, height, fieldWidthDeg));
            double ra = (Math.toDegrees(Math.atan2(sky[1], sky[0])) + 360) % 360;
            return new Coordinate(ra, Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, sky[2])))));
        }

        /** Unit camera ray of a pinhole pixel; LOST's boresight is +x. */
        static double[] cameraRay(double pixelX, double pixelY, int width, int height, double fieldWidthDeg) {
            double focal = (width - 1) / (2 * Math.tan(Math.toRadians(fieldWidthDeg / 2)));
            double vy = ((width - 1) / 2.0 - pixelX) / focal;
            double vz = ((height - 1) / 2.0 - pixelY) / focal;
            double norm = Math.sqrt(1 + vy * vy + vz * vz);
            return new double[]{1 / norm, vy / norm, vz / norm};
        }

        /** LOST stores the sky-to-camera quaternion. Apply its conjugate to a camera vector. */
        double[] toSky(double[] v) {
            double tx = 2 * (-y * v[2] + z * v[1]);
            double ty = 2 * (-z * v[0] + x * v[2]);
            double tz = 2 * (-x * v[1] + y * v[0]);
            double sx = v[0] + w * tx - y * tz + z * ty;
            double sy = v[1] + w * ty - z * tx + x * tz;
            double sz = v[2] + w * tz - x * ty + y * tx;
            double norm = Math.sqrt(sx * sx + sy * sy + sz * sz);
            return new double[]{sx / norm, sy / norm, sz / norm};
        }

        /**
         * Least-squares attitude (Wahba's problem) mapping camera rays onto catalog unit vectors,
         * by Gauss-Newton small-rotation updates from this attitude.
         */
        Attitude fitTo(List<double[]> cameraRays, List<double[]> skyVectors) {
            if (cameraRays.size() != skyVectors.size() || cameraRays.size() < 3) return this;
            double[][] m = new double[3][3];
            for (int column = 0; column < 3; column++) {
                double[] axis = new double[3];
                axis[column] = 1;
                double[] image = toSky(axis);
                for (int row = 0; row < 3; row++) m[row][column] = image[row];
            }
            for (int iteration = 0; iteration < 8; iteration++) {
                double[][] normal = new double[3][3];
                double[] rhs = new double[3];
                for (int i = 0; i < cameraRays.size(); i++) {
                    double[] u = multiply(m, cameraRays.get(i)), s = skyVectors.get(i);
                    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) normal[r][c] += (r == c ? 1 : 0) - u[r] * u[c];
                    rhs[0] += u[1] * s[2] - u[2] * s[1];
                    rhs[1] += u[2] * s[0] - u[0] * s[2];
                    rhs[2] += u[0] * s[1] - u[1] * s[0];
                }
                double[] delta = solve(normal, rhs);
                if (delta == null) return this;
                m = multiply(rotation(delta), m);
                if (Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]) < 1e-13) break;
            }
            return fromCameraToSky(m);
        }

        /** Inverse of {@link #toSky}: the attitude whose conjugate rotation is the given matrix. */
        static Attitude fromCameraToSky(double[][] m) {
            double trace = m[0][0] + m[1][1] + m[2][2], w, x, y, z;
            if (trace > 0) {
                double s = 2 * Math.sqrt(trace + 1);
                w = s / 4; x = (m[2][1] - m[1][2]) / s; y = (m[0][2] - m[2][0]) / s; z = (m[1][0] - m[0][1]) / s;
            } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
                double s = 2 * Math.sqrt(1 + m[0][0] - m[1][1] - m[2][2]);
                w = (m[2][1] - m[1][2]) / s; x = s / 4; y = (m[0][1] + m[1][0]) / s; z = (m[0][2] + m[2][0]) / s;
            } else if (m[1][1] > m[2][2]) {
                double s = 2 * Math.sqrt(1 + m[1][1] - m[0][0] - m[2][2]);
                w = (m[0][2] - m[2][0]) / s; x = (m[0][1] + m[1][0]) / s; y = s / 4; z = (m[1][2] + m[2][1]) / s;
            } else {
                double s = 2 * Math.sqrt(1 + m[2][2] - m[0][0] - m[1][1]);
                w = (m[1][0] - m[0][1]) / s; x = (m[0][2] + m[2][0]) / s; y = (m[1][2] + m[2][1]) / s; z = s / 4;
            }
            double norm = Math.sqrt(w * w + x * x + y * y + z * z);
            return new Attitude(w / norm, -x / norm, -y / norm, -z / norm);
        }

        private static double[] multiply(double[][] m, double[] v) {
            return new double[]{m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
        }

        private static double[][] multiply(double[][] a, double[][] b) {
            double[][] product = new double[3][3];
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) for (int k = 0; k < 3; k++) product[r][c] += a[r][k] * b[k][c];
            return product;
        }

        /** Rodrigues rotation matrix of a rotation vector. */
        private static double[][] rotation(double[] v) {
            double angle = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            if (angle < 1e-15) return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
            double kx = v[0] / angle, ky = v[1] / angle, kz = v[2] / angle, c = Math.cos(angle), s = Math.sin(angle), t = 1 - c;
            return new double[][]{
                    {c + kx * kx * t, kx * ky * t - kz * s, kx * kz * t + ky * s},
                    {ky * kx * t + kz * s, c + ky * ky * t, ky * kz * t - kx * s},
                    {kz * kx * t - ky * s, kz * ky * t + kx * s, c + kz * kz * t}};
        }

        private static double[] solve(double[][] a, double[] b) {
            double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                    - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                    + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
            if (!Double.isFinite(det) || Math.abs(det) < 1e-12) return null;
            double[] result = new double[3];
            for (int column = 0; column < 3; column++) {
                double[][] replaced = {a[0].clone(), a[1].clone(), a[2].clone()};
                for (int row = 0; row < 3; row++) replaced[row][column] = b[row];
                result[column] = (replaced[0][0] * (replaced[1][1] * replaced[2][2] - replaced[1][2] * replaced[2][1])
                        - replaced[0][1] * (replaced[1][0] * replaced[2][2] - replaced[1][2] * replaced[2][0])
                        + replaced[0][2] * (replaced[1][0] * replaced[2][1] - replaced[1][1] * replaced[2][0])) / det;
            }
            return result;
        }
    }
}
