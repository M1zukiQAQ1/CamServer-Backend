package edu.camserver.app.sky;

import edu.camserver.app.model.platesolve.PlateSolveStar;
import edu.camserver.app.sky.SkyProjection.Match;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SkyProjectionTest {

    private static List<Match> identifiedStars() {
        List<Match> matches = new ArrayList<>();
        for (PlateSolveStar star : SkyTestSupport.solvedFrame().stars()) {
            if (star.name() != null) {
                matches.add(new Match(star.raDeg(), star.decDeg(), star.x(), star.y()));
            }
        }
        return matches;
    }

    @Test
    void fitsTheIdentifiedStarsOfARealFrameToAFractionOfAPixel() {
        List<Match> matches = identifiedStars();
        SkyProjection projection = SkyProjection.fit(matches, 2500, 2180).orElseThrow();
        assertThat(matches).hasSize(114);
        assertThat(projection.stars()).isGreaterThanOrEqualTo(110);
        assertThat(projection.rmsPx()).isLessThan(0.3);
        assertThat(projection.maxAngleDeg()).isBetween(55.0, 62.0);
    }

    @Test
    void predictsStarsItWasNotFittedTo() {
        List<Match> matches = identifiedStars();
        List<Match> fitted = new ArrayList<>();
        List<Match> held = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            (i % 2 == 0 ? fitted : held).add(matches.get(i));
        }
        SkyProjection projection = SkyProjection.fit(fitted, 2500, 2180).orElseThrow();
        for (Match star : held) {
            double[] xy = projection.project(star.raDeg(), star.decDeg());
            assertThat(Math.hypot(xy[0] - star.x(), xy[1] - star.y())).isLessThan(1.0);
        }
    }

    @Test
    void findsBrightStarsTheSolverDetectedButDidNotIdentify() {
        SkyProjection projection = SkyProjection.fit(identifiedStars(), 2500, 2180).orElseThrow();
        double[][] stars = {{101.287155, -16.716116}, {88.792939, 7.407064}, {79.172328, 45.997991}, {114.825493, 5.224993}};
        for (double[] star : stars) {   // Sirius, Betelgeuse, Capella, Procyon
            double[] xy = projection.project(star[0], star[1]);
            double nearest = SkyTestSupport.solvedFrame().stars().stream()
                    .mapToDouble(s -> Math.hypot(s.x() - xy[0], s.y() - xy[1])).min().orElseThrow();
            assertThat(nearest).isLessThan(1.5);
        }
    }

    @Test
    void recoversASyntheticMirroredCamera() {
        // A camera looking at RA 200°, Dec -30°, rolled 123°, mirrored, with barrel distortion.
        Random random = new Random(7);
        List<Match> matches = new ArrayList<>();
        List<double[]> truth = new ArrayList<>();
        double[] axis = SkyProjection.unit(Math.toRadians(200), Math.toRadians(-30));
        while (matches.size() < 60) {
            double ra = random.nextDouble() * 360;
            double dec = Math.toDegrees(Math.asin(2 * random.nextDouble() - 1));
            double[] v = SkyProjection.unit(Math.toRadians(ra), Math.toRadians(dec));
            double theta = Math.acos(SkyProjection.dot(v, axis));
            if (theta > Math.toRadians(70)) {
                continue;
            }
            double[] east = {-Math.sin(Math.toRadians(200)), Math.cos(Math.toRadians(200)), 0};
            double[] north = {axis[1] * east[2] - axis[2] * east[1], axis[2] * east[0] - axis[0] * east[2], axis[0] * east[1] - axis[1] * east[0]};
            double phi = Math.atan2(SkyProjection.dot(v, north), SkyProjection.dot(v, east)) + Math.toRadians(123);
            double radius = 600 * theta - 25 * Math.pow(theta, 3);
            double x = 1000 + radius * Math.cos(phi);
            double y = 800 - radius * Math.sin(phi);
            truth.add(new double[] {x, y});
            matches.add(new Match(ra, dec, x + random.nextGaussian() * 0.2, y + random.nextGaussian() * 0.2));
        }
        // Three wrong identifications must not spoil the fit.
        matches.set(5, new Match(matches.get(5).raDeg(), matches.get(5).decDeg(), 100, 100));
        matches.set(17, new Match(matches.get(17).raDeg(), matches.get(17).decDeg(), 1900, 300));
        matches.set(40, new Match(matches.get(40).raDeg(), matches.get(40).decDeg(), 950, 1500));

        SkyProjection projection = SkyProjection.fit(matches, 2000, 1600).orElseThrow();
        assertThat(projection.stars()).isEqualTo(57);
        assertThat(projection.rmsPx()).isLessThan(0.5);
        for (int i = 0; i < matches.size(); i++) {
            double[] xy = projection.project(matches.get(i).raDeg(), matches.get(i).decDeg());
            assertThat(Math.hypot(xy[0] - truth.get(i)[0], xy[1] - truth.get(i)[1])).isLessThan(1.0);
        }
    }

    @Test
    void refusesStarsThatDoNotFormAProjection() {
        Random random = new Random(3);
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            matches.add(new Match(random.nextDouble() * 360, random.nextDouble() * 120 - 60,
                    random.nextDouble() * 2500, random.nextDouble() * 2180));
        }
        assertThat(SkyProjection.fit(matches, 2500, 2180)).isEmpty();
        assertThat(SkyProjection.fit(identifiedStars().subList(0, 8), 2500, 2180)).isEmpty();
    }
}
