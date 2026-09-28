package edu.camserver.app.service;

import edu.camserver.app.model.Image;
import edu.camserver.app.model.platesolve.PlateSolveCrop;
import edu.camserver.app.model.platesolve.PlateSolveResult;
import edu.camserver.app.model.platesolve.PlateSolveStar;
import edu.camserver.app.model.platesolve.PlateSolveStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.core.io.ClassPathResource;
import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlateSolveLostIntegrationTest {
    @TempDir Path directory;

    @Test void faintStarCentroidUsesWingsBelowDetectionThreshold() throws Exception {
        PlateSolveService service = service(new LostPlateSolver(false, "", "", 1, 0.05, 40), "classpath:catalogs/bright-stars.csv");
        try {
            int[] pixels = new int[41 * 41];
            for (int y = 0; y < 41; y++) for (int x = 0; x < 41; x++) {
                pixels[y * 41 + x] = (int) Math.round(10 + 80 * Math.exp(-((x - 20.3) * (x - 20.3)
                        + (y - 20.7) * (y - 20.7)) / 2));
            }
            Object seed = record("StarCandidate", 20.0, 21.0, 90, 10);
            Object measured = ReflectionTestUtils.invokeMethod(service, "refineCompactCentroid", seed, pixels, new boolean[pixels.length], 41, 41);
            assertEquals(20.3, value(measured, "x"), 0.06);
            assertEquals(20.7, value(measured, "y"), 0.06);
            Object saturated = record("StarCandidate", 20.0, 21.0, 255, 10);
            assertSame(saturated, ReflectionTestUtils.invokeMethod(service, "refineCompactCentroid", saturated, pixels, new boolean[pixels.length], 41, 41));
        } finally {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void leastSquaresAttitudeExcludesFalseSourcesAndIsValidatedOnHeldOutStars(boolean biasedTraining) throws Exception {
        FisheyeLens lens = FisheyeLens.find("QHY5III678C-57bbd14782e9f938e", 2500, 2180).orElseThrow();
        var truth = new LostPlateSolver.Attitude(1, 0, 0, 0);
        // LOST's pose is 40 arcsec off the truth unless the test biases the training stars instead.
        double tilt = Math.toRadians(40.0 / 3600) / 2;
        var initial = biasedTraining ? truth : new LostPlateSolver.Attitude(Math.cos(tilt), 0, 0, Math.sin(tilt));
        List<PlateSolveStar> detections = new ArrayList<>();
        List<String> rows = new ArrayList<>(List.of("name,raDeg,decDeg,magnitude,hr"));
        for (int i = 0; i < 24; i++) {
            double x = 900 + (i % 6) * 140, y = 850 + (i / 6) * 140;
            var point = lens.toPinhole(x, y, 1600, 100).orElseThrow();
            var sky = truth.pixelToSky(point.x(), point.y(), 1600, 1600, 100);
            // Stars with i % 3 != 0 train the fit; a common 20-arcsec error there must not survive validation.
            double decError = biasedTraining && i % 3 != 0 ? 20.0 / 3600 : 0;
            rows.add("Test " + i + "," + sky.raDeg() + "," + (sky.decDeg() + decError) + ",2," + (2000 + i));
            detections.add(new PlateSolveStar(i, x, y, x, y, 255, null, null, null, null, null, List.of(), List.of(), false));
        }
        for (int i = 0; i < 5; i++) {
            double x = 1000 + i * 100, y = 700;
            detections.add(new PlateSolveStar(24 + i, x, y, x, y, 255, null, null, null, null, null, List.of(), List.of(), false));
        }
        Path catalog = directory.resolve("catalog.csv");
        Files.write(catalog, rows);
        var solver = new LostPlateSolver(false, "", "", 1, 0.05, 40) {
            @Override boolean isAvailable() { return true; }
            @Override Run solve(Path workspace, List<Centroid> points, int width, int height, double fov) {
                return new Run(initial, "Initial verified pose");
            }
        };
        PlateSolveService service = service(solver, catalog.toString());
        try {
            Image image = new Image();
            image.setCameraId(lens.cameraId());
            Optional<PlateSolveResult> result = ReflectionTestUtils.invokeMethod(service, "completeWithLost", 99L, image,
                    new BufferedImage(2500, 2180, BufferedImage.TYPE_INT_RGB), 12,
                    new PlateSolveCrop(0, 0, 2500, 2180, 2500, 2180), detections);
            assertTrue(result.isPresent());
            String log = result.get().solution().solverLog();
            assertTrue(log.contains("5 detections excluded from refinement"), log);
            assertEquals(29, result.get().stars().size(), "Rejected detections remain available for inspection");
            assertEquals(0, result.get().solution().fieldCenterRaDeg(), biasedTraining ? 1e-9 : 1e-5);
            assertEquals(0, result.get().solution().fieldCenterDecDeg(), biasedTraining ? 1e-9 : 1e-5);
            if (biasedTraining) {
                assertTrue(log.contains("Retained the original verified attitude"), log);
            } else {
                assertTrue(log.contains("Least-squares attitude accepted"), log);
                assertTrue(result.get().stars().stream().filter(star -> star.name() != null)
                        .allMatch(star -> star.catalogMatchDistanceArcsec() < 0.05), "The fit must remove LOST's 40-arcsec offset");
            }
        } finally {
            service.shutdown();
        }
    }

    @Test void attitudeMatrixRoundTripAndLeastSquaresRecoverRotation() {
        var truth = new LostPlateSolver.Attitude(0.3, -0.5, 0.7, 0.4142);
        double norm = Math.sqrt(0.3 * 0.3 + 0.25 + 0.49 + 0.4142 * 0.4142);
        truth = new LostPlateSolver.Attitude(truth.w() / norm, truth.x() / norm, truth.y() / norm, truth.z() / norm);
        double[][] m = new double[3][3];
        for (int c = 0; c < 3; c++) {
            double[] axis = new double[3];
            axis[c] = 1;
            double[] image = truth.toSky(axis);
            for (int r = 0; r < 3; r++) m[r][c] = image[r];
        }
        var rebuilt = LostPlateSolver.Attitude.fromCameraToSky(m);
        var random = new java.util.Random(3);
        List<double[]> rays = new ArrayList<>(), sky = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double[] ray = LostPlateSolver.Attitude.cameraRay(random.nextDouble() * 1600, random.nextDouble() * 1600, 1600, 1600, 100);
            double[] expected = truth.toSky(ray), actual = rebuilt.toSky(ray);
            for (int k = 0; k < 3; k++) assertEquals(expected[k], actual[k], 1e-12);
            rays.add(ray);
            sky.add(expected);
        }
        double half = Math.toRadians(0.2) / 2;
        var start = new LostPlateSolver.Attitude(truth.w() * Math.cos(half) - truth.z() * Math.sin(half),
                truth.x() * Math.cos(half) + truth.y() * Math.sin(half),
                truth.y() * Math.cos(half) - truth.x() * Math.sin(half),
                truth.z() * Math.cos(half) + truth.w() * Math.sin(half));
        var fitted = start.fitTo(rays, sky);
        for (int i = 0; i < rays.size(); i++) {
            double[] actual = fitted.toSky(rays.get(i));
            double[] e = sky.get(i);
            double cross = Math.sqrt(Math.pow(actual[1] * e[2] - actual[2] * e[1], 2)
                    + Math.pow(actual[2] * e[0] - actual[0] * e[2], 2) + Math.pow(actual[0] * e[1] - actual[1] * e[0], 2));
            assertTrue(Math.toDegrees(Math.asin(cross)) * 3600 < 1e-4, "residual " + Math.toDegrees(Math.asin(cross)) * 3600);
        }
    }

    @Test void decenteredLensInverseMatchesForwardModel() {
        FisheyeLens lens = FisheyeLens.find("QHY5III678M-54ffe941916d2aa46", 2500, 2180).orElseThrow();
        assertNotEquals(0.0, lens.decentering1());
        for (double x = 500; x <= 2000; x += 125) {
            for (double y = 400; y <= 1800; y += 125) {
                Optional<LostPlateSolver.Centroid> pinhole = lens.toPinhole(x, y, 1600, 100);
                if (pinhole.isEmpty()) continue;
                double[] back = lens.fromPinhole(pinhole.get().x(), pinhole.get().y(), 1600, 100).orElseThrow();
                assertEquals(x, back[0], 1e-6);
                assertEquals(y, back[1], 1e-6);
            }
        }
    }

    @Test void linearFitsCentroidRecoversSubpixelPositionsIncludingSaturatedCores() throws Exception {
        PlateSolveService service = service(new LostPlateSolver(false, "", "", 1, 0.05, 40), "classpath:catalogs/bright-stars.csv");
        try {
            int size = 61;
            // x, y, amplitude, saturation level, tolerance: a clipped core loses some centring information.
            for (double[] star : new double[][]{{30.27, 29.64, 3000, 1e9, 0.03}, {30.5, 30.5, 400, 1e9, 0.03}, {29.81, 30.38, 60000, 20000, 0.1}}) {
                float[] pixels = new float[size * size];
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                    double value = 1000 + star[2] * Math.exp(-((x - star[0]) * (x - star[0]) + (y - star[1]) * (y - star[1])) / (2 * 0.9 * 0.9));
                    pixels[y * size + x] = (float) Math.min(star[3], value + ((x * 7 + y * 13) % 5 - 2));
                }
                Object linear = record("LinearPixels", size, size, pixels);
                Optional<double[]> measured = ReflectionTestUtils.invokeMethod(service, "linearCentroid", linear,
                        Math.round(star[0]) * 1.0, Math.round(star[1]) * 1.0);
                assertTrue(measured.isPresent());
                assertEquals(star[0], measured.get()[0], star[4]);
                assertEquals(star[1], measured.get()[1], star[4]);
            }
            float[] flat = new float[size * size];
            Arrays.fill(flat, 1000);
            Optional<double[]> empty = ReflectionTestUtils.invokeMethod(service, "linearCentroid",
                    record("LinearPixels", size, size, flat), 30.0, 30.0);
            assertTrue(empty.isEmpty(), "A flat background has no centroid");
            Optional<double[]> edge = ReflectionTestUtils.invokeMethod(service, "linearCentroid",
                    record("LinearPixels", size, size, flat), 5.0, 30.0);
            assertTrue(edge.isEmpty(), "The background annulus must fit inside the frame");
        } finally {
            service.shutdown();
        }
    }

    @Test void properMotionMovesCatalogStarsToTheImageEpoch() throws Exception {
        Path catalog = directory.resolve("catalog.csv");
        Files.write(catalog, List.of("name,raDeg,decDeg,magnitude,hr,pmra,pmdec",
                "Arcturus,213.915300,19.182409,-0.05,5340,-1093.39,-2000.06",
                "Still,10,10,3,1,,"));
        PlateSolveService service = service(new LostPlateSolver(false, "", "", 1, 0.05, 40), catalog.toString());
        try {
            Image image = new Image();
            image.setTimestamp(Instant.parse("2026-09-24T02:52:38Z"));
            List<?> stars = ReflectionTestUtils.invokeMethod(service, "catalogAtEpoch", image);
            double years = 26.73;
            assertEquals(19.182409 - 2000.06 * years / 3.6e6, value(stars.get(0), "decDeg"), 2e-5);
            assertEquals(213.915300 - 1093.39 * years / 3.6e6 / Math.cos(Math.toRadians(19.182409)),
                    value(stars.get(0), "raDeg"), 2e-5);
            assertEquals(10, value(stars.get(1), "raDeg"), 1e-12);
            Object hit = ReflectionTestUtils.invokeMethod(service, "matchCatalog", stars,
                    record("SkyCoordinate", value(stars.get(0), "raDeg"), value(stars.get(0), "decDeg")), 1.0 / 3600);
            assertTrue(((Optional<?>) hit).isPresent(), "Arcturus is 61 arcsec from its J2000 position in 2026");
        } finally {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void truncatedFitsWithoutReadableCompanionFails(boolean corruptPreview) throws Exception {
        Files.copy(new ClassPathResource("lost/ovl/truncated-header.fits").getInputStream(), directory.resolve("frame.fits"));
        if (corruptPreview) Files.writeString(directory.resolve("frame.jpg"), "broken preview");
        PlateSolveService service = service(new LostPlateSolver(false, "", "", 1, 0.05, 40), "classpath:catalogs/bright-stars.csv");
        try {
            Image image = new Image();
            image.setImgPath("frame.fits");
            var images = mock(ImageService.class);
            when(images.findById(100L)).thenReturn(image);
            ReflectionTestUtils.setField(service, "imageService", images);
            ReflectionTestUtils.setField(service, "imagePaths", new edu.camserver.app.config.ImagePaths(directory.toString()));
            PlateSolveResult result = service.start(100L, true, true);
            assertEquals(PlateSolveStatus.FAILED, result.status());
            assertTrue(result.message().contains("shorter than its header declares"));
            assertTrue(result.stars().isEmpty());
        } finally {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @CsvSource({"56690,2026-03-22T06:05:32.857Z,false", "56678,2026-03-22T05:01:09.870Z,false",
            "56702,2026-03-22T07:09:59.493Z,true", "34909,2025-11-24T09:59:43.707Z,false"})
    @EnabledIfEnvironmentVariable(named = "LOST_TEST_COMMAND", matches = ".+")
    void ovlSolvesAfterRimCropIncludingTruncatedFitsAndAnotherSeason(long id, String timestamp, boolean truncatedFits) throws Exception {
        Path database = Path.of(System.getenv("LOST_TEST_DATABASE"));
        Path catalog = directory.resolve("catalog.csv");
        List<String> rows = new ArrayList<>(List.of("name,raDeg,decDeg,magnitude,hr"));
        for (String line : Files.readAllLines(database.resolveSibling("bright-star-catalog.tsv"))) {
            String[] parts = line.split("\\|");
            rows.add("HR " + parts[2].trim() + "," + parts[0].trim() + "," + parts[1].trim() + ","
                    + parts[4].trim() + "," + parts[2].trim());
        }
        Files.write(catalog, rows);
        var solver = new LostPlateSolver(true, System.getenv("LOST_TEST_COMMAND"), database.toString(), 15, 0.05, 40);
        PlateSolveService service = service(solver, catalog.toString());
        try {
            Files.copy(new ClassPathResource("lost/ovl/frame-" + id + ".jpg").getInputStream(), directory.resolve("frame.jpg"));
            if (truncatedFits) Files.copy(new ClassPathResource("lost/ovl/truncated-header.fits").getInputStream(), directory.resolve("frame.fits"));
            Image image = new Image();
            image.setCameraId("QHY5III678C-57bbd14782e9f938e");
            image.setImgPath("frame");
            image.setTimestamp(Instant.parse(timestamp));
            var images = mock(ImageService.class);
            when(images.findById(id)).thenReturn(image);
            var masks = new PlateSolveMaskService(new com.fasterxml.jackson.databind.ObjectMapper(),
                    true, 0.74, 12, true, 210, 80, 64, true, 60, 500, 72, 0.35,
                    false, "", false, "", 500, 0.35, "");
            ReflectionTestUtils.setField(service, "imageService", images);
            ReflectionTestUtils.setField(service, "imagePaths", new edu.camserver.app.config.ImagePaths(directory.toString()));
            ReflectionTestUtils.setField(service, "maskService", masks);
            PlateSolveResult result = service.start(id, true, true);
            assertEquals(PlateSolveStatus.SOLVED, result.status(), result.toString());
            assertTrue(result.stars().stream().filter(star -> star.name() != null).count() >= 12, result.message());
            assertTrue(result.stars().stream().filter(star -> star.name() != null)
                    .allMatch(star -> star.catalogMatchDistanceArcsec() <= 60), "Loose catalog associations must remain unconfirmed");
            assertEquals(37.36055556, result.solution().siteLatitudeDeg(), 1e-6);
            assertEquals(-118.32666667, result.solution().siteLongitudeDeg(), 1e-6);
            Optional<Double> sidereal = ReflectionTestUtils.invokeMethod(service, "localSiderealTimeDeg", image);
            assertEquals(sidereal.orElseThrow(), result.solution().fieldCenterRaDeg(), 2.0);
            assertEquals(37.36, result.solution().fieldCenterDecDeg(), 2.0);
            assertTrue(result.solution().solverLog().contains("Adaptive outlier rejection"));
            if (truncatedFits) assertTrue(result.solution().solverLog().contains("original FITS unreadable"));
            if (id == 56702) {
                PlateSolveStar beta = result.stars().stream().min(java.util.Comparator.comparingDouble(star ->
                        Math.hypot(star.x() - 1849.436, star.y() - 1348.244))).orElseThrow();
                assertTrue(Math.hypot(beta.x() - 1849.436, beta.y() - 1348.244) < 1);
                assertNull(beta.name(), "The reported 176-arcsecond Beta Cancri association must be rejected");
            }
            FisheyeLens lens = FisheyeLens.find(image.getCameraId(), 2500, 2180).orElseThrow();
            for (PlateSolveStar star : result.stars()) {
                assertTrue(Math.hypot(star.x() - lens.centerX(), (star.y() - lens.centerY()) / lens.aspectY())
                        <= lens.detectionRadiusPixels() + 1, "Rim light entered the centroid list");
            }
            System.out.println("OVL fixture " + id + ": " + result.message() + "\n" + result.solution().solverLog());
        } finally {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unsuccessfulRequestsReturnPromptlyWithoutAnotherSolver(boolean available) throws Exception {
        var solver = new LostPlateSolver(false, "", "", 1, 0.05, 40) {
            @Override boolean isAvailable() { return available; }
        };
        PlateSolveService service = service(solver, "classpath:catalogs/bright-stars.csv");
        try {
            Image image = new Image();
            image.setImgPath("empty.png");
            image.setCameraId("empty-test");
            image.setTimestamp(Instant.parse("2026-09-05T11:58:13Z"));
            ImageIO.write(new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("empty.png").toFile());
            var images = mock(ImageService.class);
            when(images.findById(51L)).thenReturn(image);
            var masks = mock(PlateSolveMaskService.class);
            when(masks.buildIgnoreMask(any(), any(), any(), any())).thenReturn(new boolean[400 * 400]);
            ReflectionTestUtils.setField(service, "imageService", images);
            ReflectionTestUtils.setField(service, "imagePaths", new edu.camserver.app.config.ImagePaths(directory.toString()));
            ReflectionTestUtils.setField(service, "maskService", masks);
            PlateSolveResult result = assertTimeout(java.time.Duration.ofSeconds(5), () -> service.start(51L, true, true));
            assertEquals(available ? PlateSolveStatus.FAILED : PlateSolveStatus.SOLVER_UNAVAILABLE, result.status());
            assertFalse(result.solution().solved());
            assertTrue(result.stars().isEmpty());
        } finally {
            service.shutdown();
        }
    }

    @Test void calibratedLensIsRestrictedToItsCameraAndSensorRoi() {
        FisheyeLens lens = FisheyeLens.find("QHY5III678M-54ffe941916d2aa46", 2500, 2180).orElseThrow();
        assertTrue(lens.valid());
        assertTrue(FisheyeLens.find("another-camera", 2500, 2180).isEmpty());
        assertTrue(FisheyeLens.find(lens.cameraId(), 3856, 2180).isEmpty());
        var center = lens.toPinhole(lens.centerX(), lens.centerY(), 1600, 100).orElseThrow();
        assertEquals(799.5, center.x(), 1e-9);
        assertEquals(799.5, center.y(), 1e-9);
        assertTrue(lens.toPinhole(0, 0, 1600, 100).isEmpty(), "Do not extrapolate beyond the calibrated field");
        assertTrue(lens.toPinhole(Double.NaN, 500, 1600, 100).isEmpty());
    }

    @Test void noiseCannotBePromotedByAnAttitude() throws Exception {
        LostPlateSolver falseCandidate = new LostPlateSolver(false, "", "", 1, 0.05, 40) {
            @Override boolean isAvailable() { return true; }
            @Override Run solve(Path workspace, List<Centroid> points, int width, int height, double fov) {
                return new Run(new Attitude(0.544053, 0.706367, 0.450646, 0.04439), "Uncorroborated candidate");
            }
        };
        PlateSolveService service = service(falseCandidate, "classpath:catalogs/bright-stars.csv");
        try {
            Image image = new Image();
            image.setCameraId("QHY5III678M-54ffe941916d2aa46");
            image.setTimestamp(Instant.parse("2026-09-05T11:58:13.050Z"));
            var source = new BufferedImage(2500, 2180, BufferedImage.TYPE_INT_RGB);
            var crop = new PlateSolveCrop(0, 0, 2500, 2180, 2500, 2180);
            var random = new java.util.Random(57316);
            List<PlateSolveStar> noise = new ArrayList<>();
            for (int i = 0; i < 290; i++) {
                double x = 600 + random.nextDouble() * 1250, y = 450 + random.nextDouble() * 1250;
                noise.add(new PlateSolveStar(i, x, y, x, y, 255, null, null, null, null, null,
                        List.of(), List.of(), false));
            }
            Optional<?> lost = ReflectionTestUtils.invokeMethod(service, "completeWithLost",
                    57316L, image, source, 12, crop, noise);
            assertTrue(lost.isEmpty(), "An attitude needs independent measured catalog confirmations");
        } finally {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @CsvSource({"57316,2026-09-05T11:58:13.050Z,44.9247,33.5666",
            "57304,2026-09-05T10:58:03.557Z,29.8725,33.55",
            "57292,2026-09-05T09:57:53.987Z,14.8175,33.5364"})
    @EnabledIfEnvironmentVariable(named = "LOST_TEST_COMMAND", matches = ".+")
    void realNightFrameSolvesWithMeasuredLensAndApertureFlux(long id, String timestamp, double centerRa, double centerDec) throws Exception {
        Path database = Path.of(System.getenv("LOST_TEST_DATABASE"));
        Path catalog = directory.resolve("catalog.csv");
        List<String> rows = new ArrayList<>(List.of("name,raDeg,decDeg,magnitude,hr"));
        for (String line : Files.readAllLines(database.resolveSibling("bright-star-catalog.tsv"))) {
            String[] parts = line.split("\\|");
            rows.add("HR " + parts[2].trim() + "," + parts[0].trim() + "," + parts[1].trim() + ","
                    + parts[4].trim() + "," + parts[2].trim());
        }
        Files.write(catalog, rows);
        var solver = new LostPlateSolver(true, System.getenv("LOST_TEST_COMMAND"), database.toString(), 30, 0.05, 40);
        PlateSolveService service = service(solver, catalog.toString());
        try {
            Image image = new Image();
            image.setCameraId("QHY5III678M-54ffe941916d2aa46");
            image.setTimestamp(Instant.parse(timestamp));
            BufferedImage source = ImageIO.read(new ClassPathResource("lost/frame-" + id + ".jpg").getInputStream());
            List<PlateSolveStar> detections = new ArrayList<>();
            String csv = new String(new ClassPathResource("lost/frame-" + id + ".csv").getInputStream().readAllBytes());
            for (String row : csv.lines().skip(1).toList()) {
                String[] p = row.split(",");
                double x = Double.parseDouble(p[1]), y = Double.parseDouble(p[2]);
                detections.add(new PlateSolveStar(Integer.parseInt(p[0]), x, y, x, y, Integer.parseInt(p[3]),
                        null, null, null, null, null, List.of(), List.of(), false));
            }
            Optional<PlateSolveResult> actual = ReflectionTestUtils.invokeMethod(service, "completeWithLost",
                    id, image, source, 12, new PlateSolveCrop(0, 0, 2500, 2180, 2500, 2180), detections);
            assertTrue(actual.isPresent(), () -> service.getStatus(id).toString());
            var result = actual.orElseThrow();
            assertTrue(result.message().startsWith("LOST Pyramid"));
            assertEquals(centerRa, result.solution().fieldCenterRaDeg(), 0.1, result.solution().solverLog());
            assertEquals(centerDec, result.solution().fieldCenterDecDeg(), 0.1);
            assertEquals(detections.size(), result.stars().size());
            assertTrue(result.stars().stream().filter(star -> star.name() != null).count() >= 20);
            assertTrue(result.stars().stream().filter(star -> star.name() != null)
                    .allMatch(star -> star.catalogMatchDistanceArcsec() <= 60));
            // Rigel is a measured detection, not a synthesized catalog pixel.
            if (id == 57316) {
                PlateSolveStar rigel = result.stars().stream().filter(star -> star.id() == 2).findFirst().orElseThrow();
                assertEquals(78.634583, rigel.raDeg(), 0.05);
                assertEquals(-8.201667, rigel.decDeg(), 0.05);
                assertEquals(detections.get(1).x(), rigel.x());
            }
            assertTrue(result.stars().stream().anyMatch(star -> star.catalogMatchDistanceArcsec() != null
                    && star.catalogMatchDistanceArcsec() > 0));
        } finally {
            service.shutdown();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LOST_TEST_COMMAND", matches = ".+")
    void nativeSolverHandlesFisheyeHandednessAndPreservesMeasuredDetections() throws Exception {
        Path database = Path.of(System.getenv("LOST_TEST_DATABASE"));
        Path catalog = directory.resolve("catalog.csv");
        List<String> rows = new ArrayList<>(List.of("name,raDeg,decDeg,magnitude,hr"));
        for (String line : Files.readAllLines(database.resolveSibling("bright-star-catalog.tsv"))) {
            String[] parts = line.split("\\|");
            rows.add("HR " + parts[2].trim() + "," + parts[0].trim() + "," + parts[1].trim() + ","
                    + parts[4].trim() + "," + parts[2].trim());
        }
        Files.write(catalog, rows);
        var solver = new LostPlateSolver(true, System.getenv("LOST_TEST_COMMAND"), database.toString(), 30, 0.05, 40);
        PlateSolveService service = service(solver, catalog.toString());
        try {
            Image image = new Image();
            image.setCameraId("synthetic");
            image.setTimestamp(Instant.parse("2026-01-15T04:00:00Z"));
            Optional<Double> sidereal = ReflectionTestUtils.invokeMethod(service, "localSiderealTimeDeg", image);
            BufferedImage source = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
            var graphics = source.createGraphics();
            graphics.setColor(new Color(25, 25, 25));
            graphics.fillRect(0, 0, 900, 900);
            graphics.dispose();
            Object geometry = record("AllSkyGeometry", 449.5, 449.5, 450.0);
            PlateSolveCrop crop = new PlateSolveCrop(0, 0, 900, 900, 900, 900);
            List<PlateSolveStar> detections = new ArrayList<>();
            for (String row : rows.subList(1, rows.size())) {
                String[] parts = row.split(",");
                double ra = Double.parseDouble(parts[1]), dec = Double.parseDouble(parts[2]), mag = Double.parseDouble(parts[3]);
                if (mag > 4.5) continue;
                Object horizontal = ReflectionTestUtils.invokeMethod(service, "skyToHorizontal", ra, dec, sidereal.orElseThrow());
                if (value(horizontal, "altitudeDeg") < 20) continue;
                Optional<?> point = ReflectionTestUtils.invokeMethod(service, "projectHorizontal", geometry, crop,
                        value(horizontal, "altitudeDeg"), value(horizontal, "azimuthDeg"), 27.0, 1.0);
                if (point.isEmpty()) continue;
                double x = value(point.get(), "x"), y = value(point.get(), "y");
                detections.add(new PlateSolveStar(detections.size() + 1, x, y, x, y, (int) (220 - mag * 20),
                        null, null, null, null, null, List.of(), List.of(), false));
            }
            Optional<PlateSolveResult> result = ReflectionTestUtils.invokeMethod(service, "completeWithLost",
                    42L, image, source, 12, crop, detections);
            assertTrue(result.isPresent(), () -> service.getStatus(42).toString());
            assertEquals(PlateSolveStatus.SOLVED, result.get().status());
            assertTrue(result.get().message().startsWith("LOST Pyramid"));
            assertEquals(sidereal.orElseThrow(), result.get().solution().fieldCenterRaDeg(), 0.02, result.get().solution().solverLog());
            assertEquals(34.41403, result.get().solution().fieldCenterDecDeg(), 0.02);
            assertEquals(detections.size(), result.get().stars().size());
            assertEquals(detections.get(0).x(), result.get().stars().get(0).x());
        } finally {
            service.shutdown();
        }
    }

    private PlateSolveService service(LostPlateSolver solver, String catalog) {
        return new PlateSolveService(null, null, null, null, solver,
                true, "missing-imcopy", directory.toString(),
                5, 800, 80, 18, 95, 12, 32, 18, 42, 0.9985, 0.9994,
                34.41403, -119.843,
                catalog, 0.05, false, "", "", 720, 8, 30000, 500, 1);
    }

    private Object record(String name, Object... args) throws Exception {
        Class<?> type = Class.forName(PlateSolveService.class.getName() + "$" + name);
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private double value(Object record, String field) {
        return ((Number) ReflectionTestUtils.invokeMethod(record, field)).doubleValue();
    }
}
