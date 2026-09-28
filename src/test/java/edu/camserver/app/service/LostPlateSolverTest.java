package edu.camserver.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LostPlateSolverTest {
    @TempDir Path directory;

    @Test void rejectsUnknownMalformedAndNonUnitAttitudes() {
        for (String value : List.of("", "attitude_known 0", "attitude_known 1",
                attitude("NaN", "0", "0", "0"), attitude("0", "0", "0", "0"),
                attitude("2", "0", "0", "0"), attitude("1", "0", "0", "0") + "\nattitude_known 0")) {
            assertTrue(LostPlateSolver.parseAttitude(value).isEmpty(), value);
        }
    }

    @Test void conjugatesLostQuaternionAndHonorsPixelCentersAndHandedness() {
        double s = Math.sqrt(0.5);
        var rotation = LostPlateSolver.parseAttitude(attitude("" + s, "0", "0", "" + -s)).orElseThrow();
        var center = rotation.pixelToSky(799.5, 799.5, 1600, 1600, 100);
        assertEquals(90, center.raDeg(), 1e-9);
        assertEquals(0, center.decDeg(), 1e-9);
        assertEquals(40, rotation.pixelToSky(1599, 799.5, 1600, 1600, 100).raDeg(), 1e-9);
        assertEquals(50, rotation.pixelToSky(799.5, 0, 1600, 1600, 100).decDeg(), 1e-9);
        var oppositeSign = LostPlateSolver.parseAttitude(attitude("" + -s, "0", "0", "" + s)).orElseThrow();
        assertEquals(center.raDeg(), oppositeSign.pixelToSky(799.5, 799.5, 1600, 1600, 100).raDeg(), 1e-9);
    }

    @Test void absentInstallationAndTooFewStarsFallBack() {
        var solver = new LostPlateSolver(true, directory.resolve("missing").toString(),
                directory.resolve("database").toString(), 1, 0.05, 40);
        assertFalse(solver.isAvailable());
        assertDoesNotThrow(() -> assertNull(solver.solve(directory, List.of(), 1600, 1600, 100).attitude()));
    }

    @Test void handlesProcessFailureTimeoutAndFreshOutputs() throws Exception {
        Path database = directory.resolve("db.dat");
        Files.writeString(database, "test");
        Files.writeString(directory.resolve("bright-star-catalog.tsv"), "test");
        Path command = directory.resolve("fake lost");
        Files.writeString(command, """
                #!/bin/sh
                while [ "$#" -gt 0 ]; do
                    if [ "$1" = '--print-attitude' ]; then
                        shift
                        printf 'attitude_known 1\\nattitude_real 1\\nattitude_i 0\\nattitude_j 0\\nattitude_k 0\\n' > "$1"
                    fi
                    shift
                done
                """);
        assertTrue(command.toFile().setExecutable(true));
        var solver = new LostPlateSolver(true, command.toString(), database.toString(), 1, 0.05, 40);
        List<LostPlateSolver.Centroid> stars = List.of(new LostPlateSolver.Centroid(20, 20),
                new LostPlateSolver.Centroid(40, 20), new LostPlateSolver.Centroid(60, 20),
                new LostPlateSolver.Centroid(20, 60), new LostPlateSolver.Centroid(40, 60),
                new LostPlateSolver.Centroid(60, 60));
        assertNotNull(solver.solve(directory, stars, 100, 100, 100).attitude());
        Files.writeString(command, "#!/bin/sh\nexit 0\n");
        assertNull(solver.solve(directory, stars, 100, 100, 100).attitude(), "Never reuse a previous attitude");
        Files.writeString(command, "#!/bin/sh\nexit 1\n");
        assertNull(solver.solve(directory, stars, 100, 100, 100).attitude());
        Files.writeString(command, "#!/bin/sh\nexec sleep 10\n");
        assertTimeout(Duration.ofSeconds(4), () -> {
            var run = solver.solve(directory, stars, 100, 100, 100);
            assertNull(run.attitude());
            assertTrue(run.log().contains("timed out"));
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LOST_TEST_COMMAND", matches = ".+")
    void nativePyramidRecoversRotatedWideFieldWithFalseDetections() throws Exception {
        Path database = Path.of(System.getenv("LOST_TEST_DATABASE"));
        var solver = new LostPlateSolver(true, System.getenv("LOST_TEST_COMMAND"), database.toString(), 30, 0.05, 40);
        assertTrue(solver.isAvailable());
        // Generate a field independently with an orthonormal celestial basis, including nonzero roll.
        double ra = Math.toRadians(88), dec = Math.toRadians(7), roll = Math.toRadians(31);
        double focal = 799.5 / Math.tan(Math.toRadians(50));
        List<double[]> catalog = Files.readAllLines(database.resolveSibling("bright-star-catalog.tsv")).stream()
                .filter(line -> !line.isBlank()).map(line -> line.split("\\|"))
                .map(parts -> new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[4])})
                .sorted(Comparator.comparingDouble(row -> row[2])).toList();
        List<LostPlateSolver.Centroid> stars = new ArrayList<>(List.of(
                new LostPlateSolver.Centroid(251.3, 422.7), new LostPlateSolver.Centroid(621.9, 1273.2),
                new LostPlateSolver.Centroid(1100.1, 820.8), new LostPlateSolver.Centroid(1370.8, 275.4)));
        List<double[]> expected = new ArrayList<>();
        for (double[] star : catalog) {
            double a = Math.toRadians(star[0]), d = Math.toRadians(star[1]);
            double forward = Math.sin(d) * Math.sin(dec) + Math.cos(d) * Math.cos(dec) * Math.cos(a - ra);
            double west = -Math.cos(d) * Math.sin(a - ra);
            double north = Math.sin(d) * Math.cos(dec) - Math.cos(d) * Math.sin(dec) * Math.cos(a - ra);
            double x = 799.5 + focal * (west * Math.cos(roll) + north * Math.sin(roll)) / forward;
            double y = 799.5 - focal * (-west * Math.sin(roll) + north * Math.cos(roll)) / forward;
            if (forward <= 0 || x < 50 || x > 1550 || y < 50 || y > 1550
                    || stars.stream().anyMatch(p -> Math.hypot(p.x() - x, p.y() - y) < 15)) continue;
            stars.add(new LostPlateSolver.Centroid(x, y));
            expected.add(new double[]{x, y, star[0], star[1]});
            if (stars.size() == 40) break;
        }
        assertEquals(40, stars.size());
        var run = solver.solve(directory, stars, 1600, 1600, 100);
        assertNotNull(run.attitude(), run.log());
        var center = run.attitude().pixelToSky(799.5, 799.5, 1600, 1600, 100);
        assertEquals(88, center.raDeg(), 0.01, run.log());
        assertEquals(7, center.decDeg(), 0.01, run.log());
        for (double[] star : expected) {
            var actual = run.attitude().pixelToSky(star[0], star[1], 1600, 1600, 100);
            assertEquals(star[2], actual.raDeg(), 0.02);
            assertEquals(star[3], actual.decDeg(), 0.02);
        }
    }

    private String attitude(String w, String x, String y, String z) {
        return "attitude_known 1\nattitude_real " + w + "\nattitude_i " + x + "\nattitude_j " + y + "\nattitude_k " + z;
    }
}
