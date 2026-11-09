package edu.camserver.app.sky;

import edu.camserver.app.model.platesolve.PlateSolveResult;
import edu.camserver.app.model.platesolve.PlateSolveStar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Runs on the production plate solution of frame 85123: a clear, moonless November morning. */
class SkyServiceTest {
    private final SkyService service = new SkyService(null, null, new SkyCatalog(SkyTestSupport.MAPPER), 3.5, 55, 4);

    private SkyView view(PlateSolveResult result) {
        return service.build(result, SkyTestSupport.CAPTURED_AT);
    }

    @Test
    void aClearSkyShowsEveryExpectedStar() {
        SkyView view = view(SkyTestSupport.solvedFrame());
        assertThat(view.status()).isEqualTo(SkyView.Status.OK);
        assertThat(view.fit().rmsPx()).isLessThan(0.3);
        SkyView.Quality quality = view.quality();
        assertThat(quality.expected()).isBetween(55, 75);
        assertThat(quality.detected()).isEqualTo(quality.expected());
        assertThat(quality.clearFraction()).isEqualTo(1.0);
        assertThat(quality.limitingMagnitude()).isBetween(4.0, 4.5);
        assertThat(quality.bins().stream().mapToInt(SkyView.MagnitudeBin::expected).sum()).isGreaterThan(300);
    }

    @Test
    void cloudOverHalfTheSkyHalvesTheClearFraction() {
        PlateSolveResult clear = SkyTestSupport.solvedFrame();
        // Keep the identified stars (the plate solution stands) but lose every other source left of centre.
        List<PlateSolveStar> stars = clear.stars().stream().filter(s -> s.name() != null || s.x() > 1250).toList();
        List<PlateSolveStar> thinned = stars.stream().filter(s -> s.x() > 1250 || s.id() % 4 == 0).toList();
        SkyView view = view(new PlateSolveResult(clear.imgId(), clear.status(), clear.message(), false, clear.generatedAt(),
                clear.crop(), clear.solution(), null, thinned));
        assertThat(view.status()).isEqualTo(SkyView.Status.OK);
        assertThat(view.quality().clearFraction()).isBetween(0.45, 0.75);
    }

    @Test
    void marsAndJupiterAreOnTheSourcesTheSolverLeftUnidentified() {
        SkyView view = view(SkyTestSupport.solvedFrame());
        assertThat(view.overlay().bodies()).extracting(SkyView.Body::name).containsExactly("Mars", "Jupiter");
        for (SkyView.Body body : view.overlay().bodies()) {
            assertThat(body.detected()).isTrue();
            double nearest = SkyTestSupport.solvedFrame().stars().stream()
                    .filter(s -> s.name() == null && s.brightness() == 255)
                    .mapToDouble(s -> Math.hypot(s.x() - body.x(), s.y() - body.y())).min().orElseThrow();
            assertThat(nearest).isLessThan(2.0);
        }
    }

    @Test
    void drawsTheConstellationsAndNamesTheStarsInView() {
        SkyView.Overlay overlay = view(SkyTestSupport.solvedFrame()).overlay();
        assertThat(overlay.constellations()).extracting(SkyView.Constellation::name)
                .contains("Orion", "Gemini", "Auriga", "Taurus", "Ursa Major")
                .doesNotContain("Scorpius", "Crux", "Sagittarius");
        assertThat(overlay.stars()).extracting(SkyView.Label::name).contains("Sirius", "Betelgeuse", "Capella", "Rigel");

        SkyView.Constellation orion = overlay.constellations().stream().filter(c -> c.id().equals("Ori")).findFirst().orElseThrow();
        assertThat(orion.label()).isNotNull();
        // Betelgeuse is a vertex of Orion's figure: some line passes through its pixel.
        SkyView.Label betelgeuse = overlay.stars().stream().filter(s -> s.name().equals("Betelgeuse")).findFirst().orElseThrow();
        double nearest = orion.lines().stream().flatMap(List::stream)
                .mapToDouble(p -> Math.hypot(p[0] - betelgeuse.x(), p[1] - betelgeuse.y())).min().orElseThrow();
        assertThat(nearest).isCloseTo(0, within(2.0));
        // Lines are drawn in short steps and stay on the picture.
        for (SkyView.Constellation constellation : overlay.constellations()) {
            for (List<double[]> line : constellation.lines()) {
                assertThat(line.size()).isGreaterThan(1);
                for (int i = 0; i < line.size(); i++) {
                    assertThat(line.get(i)[0]).isBetween(0.0, 2500.0);
                    assertThat(line.get(i)[1]).isBetween(0.0, 2180.0);
                    if (i > 0) {
                        assertThat(Math.hypot(line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1])).isLessThan(40);
                    }
                }
            }
        }
    }

    @Test
    void aSolutionWithoutEnoughIdentifiedStarsIsReportedNotDrawn() {
        PlateSolveResult clear = SkyTestSupport.solvedFrame();
        List<PlateSolveStar> few = clear.stars().stream().filter(s -> s.name() == null || s.id() < 30).toList();
        SkyView view = view(new PlateSolveResult(clear.imgId(), clear.status(), clear.message(), false, clear.generatedAt(),
                clear.crop(), clear.solution(), null, few));
        assertThat(view.status()).isEqualTo(SkyView.Status.FIT_FAILED);
        assertThat(view.overlay()).isNull();
    }
}
