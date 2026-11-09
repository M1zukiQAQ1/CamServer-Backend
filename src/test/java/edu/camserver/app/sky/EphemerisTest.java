package edu.camserver.app.sky;

import edu.camserver.app.sky.Ephemeris.Body;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EphemerisTest {

    /** The worked example of the method's description: 1990-04-19 00:00 UT. */
    @Test
    void reproducesThePublishedExample() {
        double d = Ephemeris.dayNumber(Instant.parse("1990-04-19T00:00:00Z"));
        assertThat(d).isEqualTo(-3543.0);

        double[] sun = Ephemeris.equatorialOfDate(Body.SUN, d);
        assertThat(sun[0]).isCloseTo(26.6581, within(0.001));
        assertThat(sun[1]).isCloseTo(11.0084, within(0.001));

        double[] moon = Ephemeris.equatorialOfDate(Body.MOON, d);
        assertThat(moon[0]).isCloseTo(309.5011, within(0.001));
        assertThat(moon[1]).isCloseTo(-19.1032, within(0.001));

        // The same Moon seen from 60° N, 15° E
        double[] topocentric = Ephemeris.moonTopocentric(moon, d, 60, 15);
        assertThat(topocentric[0]).isCloseTo(310.0017, within(0.002));
        assertThat(topocentric[1]).isCloseTo(-19.8790, within(0.002));
    }

    @Test
    void planetsAreWhereKnownEventsPutThem() {
        // Great conjunction: Jupiter and Saturn 0.1° apart
        assertThat(separation(Body.JUPITER, Body.SATURN, "2020-12-21T18:20:00Z")).isCloseTo(0.10, within(0.03));
        // Transit of Mercury: on the Sun's disc (radius 0.27°)
        assertThat(separation(Body.MERCURY, Body.SUN, "2019-11-11T15:20:00Z")).isLessThan(0.05);
        // Inferior conjunction of Venus, 4.8° north of the Sun
        assertThat(separation(Body.VENUS, Body.SUN, "2022-01-09T00:48:00Z")).isCloseTo(4.85, within(0.1));
        // Oppositions: opposite the Sun, less the planet's ecliptic latitude
        assertThat(separation(Body.MARS, Body.SUN, "2020-10-13T23:20:00Z")).isGreaterThan(176.5);
        assertThat(separation(Body.JUPITER, Body.SUN, "2022-09-26T19:33:00Z")).isGreaterThan(178);
        assertThat(separation(Body.SATURN, Body.SUN, "2023-08-27T08:20:00Z")).isGreaterThan(177.5);
        // Total solar eclipse of 2024-04-08, greatest eclipse: geocentric Moon within 0.4° of the Sun
        assertThat(separation(Body.MOON, Body.SUN, "2024-04-08T18:18:00Z")).isLessThan(0.45);
    }

    @Test
    void positionsAreReferredToJ2000() {
        // 26 years of precession move the equinox 0.36° along the ecliptic.
        Instant at = Instant.parse("2026-03-20T12:00:00Z");
        double[] ofDate = Ephemeris.equatorialOfDate(Body.SUN, Ephemeris.dayNumber(at));
        Ephemeris.Position j2000 = Ephemeris.position(Body.SUN, at, 0, 0);
        assertThat(SkyTestSupport.separationDeg(ofDate[0], ofDate[1], j2000.raDeg(), j2000.decDeg()))
                .isCloseTo(0.366, within(0.01));
    }

    private static double separation(Body a, Body b, String instant) {
        Instant at = Instant.parse(instant);
        // Geocentric for both: the Moon's parallax would otherwise depend on the site.
        double[] first = Ephemeris.equatorialOfDate(a, Ephemeris.dayNumber(at));
        double[] second = Ephemeris.equatorialOfDate(b, Ephemeris.dayNumber(at));
        return SkyTestSupport.separationDeg(first[0], first[1], second[0], second[1]);
    }
}
