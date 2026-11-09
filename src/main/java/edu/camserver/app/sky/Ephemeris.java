package edu.camserver.app.sky;

import java.time.Instant;

/**
 * Low-precision positions of the Sun, Moon and naked-eye planets (Paul Schlyter's method,
 * https://stjarnhimlen.se/comp/ppcomp.html): about an arc minute for the planets and a few for
 * the Moon, far inside one pixel of an all-sky frame (roughly 4 arc minutes).
 *
 * <p>Results are J2000 equatorial coordinates, the frame of the star catalogue. The Moon is
 * corrected for the observer's position on the Earth (up to 1°); the planets need no such correction.
 */
public final class Ephemeris {

    public enum Body {
        SUN("Sun"), MOON("Moon"), MERCURY("Mercury"), VENUS("Venus"), MARS("Mars"), JUPITER("Jupiter"), SATURN("Saturn");

        private final String label;

        Body(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Position(double raDeg, double decDeg) {
    }

    private Ephemeris() {
    }

    /** Days since 1999-12-31 00:00 UT, the time argument of the orbital elements. */
    static double dayNumber(Instant at) {
        return at.toEpochMilli() / 86_400_000.0 - 10_956.0;
    }

    /** Geocentric position, except the Moon, which is as seen from the given site. */
    public static Position position(Body body, Instant at, double latitudeDeg, double longitudeDeg) {
        double d = dayNumber(at);
        double[] ofDate = equatorialOfDate(body, d);
        if (body == Body.MOON) {
            ofDate = moonTopocentric(ofDate, d, latitudeDeg, longitudeDeg);
        }
        return toJ2000(ofDate[0], ofDate[1], d);
    }

    /** Geocentric position in the equator and equinox of date: {raDeg, decDeg, distance}. */
    static double[] equatorialOfDate(Body body, double d) {
        double[] sun = heliocentric(elements(Body.SUN, d));   // the Sun as seen from the Earth
        double x;
        double y;
        double z;
        switch (body) {
            case SUN -> {
                x = sun[0];
                y = sun[1];
                z = 0;
            }
            case MOON -> {
                double[] moon = perturbedMoon(d);
                x = moon[0];
                y = moon[1];
                z = moon[2];
            }
            default -> {
                double[] planet = heliocentric(elements(body, d));
                if (body == Body.JUPITER || body == Body.SATURN) {
                    planet = perturbedGiant(body, planet, d);
                }
                x = planet[0] + sun[0];
                y = planet[1] + sun[1];
                z = planet[2];
            }
        }
        double obliquity = Math.toRadians(23.4393 - 3.563e-7 * d);
        double ye = y * Math.cos(obliquity) - z * Math.sin(obliquity);
        double ze = y * Math.sin(obliquity) + z * Math.cos(obliquity);
        return new double[] {
                mod360(Math.toDegrees(Math.atan2(ye, x))),
                Math.toDegrees(Math.atan2(ze, Math.hypot(x, ye))),
                Math.sqrt(x * x + y * y + z * z)};
    }

    // ---- orbital elements: N, i, w (degrees), a, e, M (degrees) ------------------------------

    private static double[] elements(Body body, double d) {
        return switch (body) {
            case SUN -> new double[] {0, 0, 282.9404 + 4.70935e-5 * d, 1, 0.016709 - 1.151e-9 * d, 356.0470 + 0.9856002585 * d};
            case MOON -> new double[] {125.1228 - 0.0529538083 * d, 5.1454, 318.0634 + 0.1643573223 * d, 60.2666, 0.054900,
                    115.3654 + 13.0649929509 * d};
            case MERCURY -> new double[] {48.3313 + 3.24587e-5 * d, 7.0047 + 5.00e-8 * d, 29.1241 + 1.01444e-5 * d, 0.387098,
                    0.205635 + 5.59e-10 * d, 168.6562 + 4.0923344368 * d};
            case VENUS -> new double[] {76.6799 + 2.46590e-5 * d, 3.3946 + 2.75e-8 * d, 54.8910 + 1.38374e-5 * d, 0.723330,
                    0.006773 - 1.302e-9 * d, 48.0052 + 1.6021302244 * d};
            case MARS -> new double[] {49.5574 + 2.11081e-5 * d, 1.8497 - 1.78e-8 * d, 286.5016 + 2.92961e-5 * d, 1.523688,
                    0.093405 + 2.516e-9 * d, 18.6021 + 0.5240207766 * d};
            case JUPITER -> new double[] {100.4542 + 2.76854e-5 * d, 1.3030 - 1.557e-7 * d, 273.8777 + 1.64505e-5 * d, 5.20256,
                    0.048498 + 4.469e-9 * d, 19.8950 + 0.0830853001 * d};
            case SATURN -> new double[] {113.6634 + 2.38980e-5 * d, 2.4886 - 1.081e-7 * d, 339.3939 + 2.97661e-5 * d, 9.55475,
                    0.055546 - 9.499e-9 * d, 316.9670 + 0.0334442282 * d};
        };
    }

    /** Position in the orbit's own ecliptic frame, centred on the body it orbits. */
    private static double[] heliocentric(double[] el) {
        double n = Math.toRadians(el[0]);
        double i = Math.toRadians(el[1]);
        double e = el[4];
        double m = Math.toRadians(mod360(el[5]));
        double eccentric = m + e * Math.sin(m) * (1 + e * Math.cos(m));
        for (int k = 0; k < 20; k++) {
            eccentric -= (eccentric - e * Math.sin(eccentric) - m) / (1 - e * Math.cos(eccentric));
        }
        double xv = el[3] * (Math.cos(eccentric) - e);
        double yv = el[3] * Math.sqrt(1 - e * e) * Math.sin(eccentric);
        double r = Math.hypot(xv, yv);
        double vw = Math.atan2(yv, xv) + Math.toRadians(el[2]);
        return new double[] {
                r * (Math.cos(n) * Math.cos(vw) - Math.sin(n) * Math.sin(vw) * Math.cos(i)),
                r * (Math.sin(n) * Math.cos(vw) + Math.cos(n) * Math.sin(vw) * Math.cos(i)),
                r * Math.sin(vw) * Math.sin(i)};
    }

    private static double[] perturbedMoon(double d) {
        double[] el = elements(Body.MOON, d);
        double[] sunEl = elements(Body.SUN, d);
        double[] xyz = heliocentric(el);
        double lon = Math.toDegrees(Math.atan2(xyz[1], xyz[0]));
        double lat = Math.toDegrees(Math.atan2(xyz[2], Math.hypot(xyz[0], xyz[1])));
        double r = Math.sqrt(xyz[0] * xyz[0] + xyz[1] * xyz[1] + xyz[2] * xyz[2]);
        double mm = el[5];
        double ms = sunEl[5];
        double moonLongitude = mm + el[2] + el[0];
        double elongation = moonLongitude - (ms + sunEl[2]);
        double f = moonLongitude - el[0];
        lon += -1.274 * sin(mm - 2 * elongation) + 0.658 * sin(2 * elongation) - 0.186 * sin(ms)
                - 0.059 * sin(2 * mm - 2 * elongation) - 0.057 * sin(mm - 2 * elongation + ms)
                + 0.053 * sin(mm + 2 * elongation) + 0.046 * sin(2 * elongation - ms) + 0.041 * sin(mm - ms)
                - 0.035 * sin(elongation) - 0.031 * sin(mm + ms) - 0.015 * sin(2 * f - 2 * elongation)
                + 0.011 * sin(mm - 4 * elongation);
        lat += -0.173 * sin(f - 2 * elongation) - 0.055 * sin(mm - f - 2 * elongation)
                - 0.046 * sin(mm + f - 2 * elongation) + 0.033 * sin(f + 2 * elongation) + 0.017 * sin(2 * mm + f);
        r += -0.58 * cos(mm - 2 * elongation) - 0.46 * cos(2 * elongation);
        return new double[] {r * cos(lon) * cos(lat), r * sin(lon) * cos(lat), r * sin(lat)};
    }

    /** Jupiter and Saturn pull on each other: up to 0.3° and 0.8° respectively. */
    private static double[] perturbedGiant(Body body, double[] xyz, double d) {
        double mj = mod360(elements(Body.JUPITER, d)[5]);
        double ms = mod360(elements(Body.SATURN, d)[5]);
        double lon = Math.toDegrees(Math.atan2(xyz[1], xyz[0]));
        double lat = Math.toDegrees(Math.atan2(xyz[2], Math.hypot(xyz[0], xyz[1])));
        double r = Math.sqrt(xyz[0] * xyz[0] + xyz[1] * xyz[1] + xyz[2] * xyz[2]);
        if (body == Body.JUPITER) {
            lon += -0.332 * sin(2 * mj - 5 * ms - 67.6) - 0.056 * sin(2 * mj - 2 * ms + 21) + 0.042 * sin(3 * mj - 5 * ms + 21)
                    - 0.036 * sin(mj - 2 * ms) + 0.022 * cos(mj - ms) + 0.023 * sin(2 * mj - 3 * ms + 52)
                    - 0.016 * sin(mj - 5 * ms - 69);
        } else {
            lon += 0.812 * sin(2 * mj - 5 * ms - 67.6) - 0.229 * cos(2 * mj - 4 * ms - 2) + 0.119 * sin(mj - 2 * ms - 3)
                    + 0.046 * sin(2 * mj - 6 * ms - 69) + 0.014 * sin(mj - 3 * ms + 32);
            lat += -0.020 * cos(2 * mj - 4 * ms - 2) + 0.018 * sin(2 * mj - 6 * ms - 49);
        }
        return new double[] {r * cos(lon) * cos(lat), r * sin(lon) * cos(lat), r * sin(lat)};
    }

    /** Moves the Moon from the Earth's centre to the observer: {raDeg, decDeg, distance in Earth radii}. */
    static double[] moonTopocentric(double[] geocentric, double d, double latitudeDeg, double longitudeDeg) {
        double parallax = Math.toDegrees(Math.asin(1 / geocentric[2]));
        double geocentricLatitude = latitudeDeg - 0.1924 * sin(2 * latitudeDeg);
        double rho = 0.99833 + 0.00167 * cos(2 * latitudeDeg);
        double sunLongitude = elements(Body.SUN, d)[5] + elements(Body.SUN, d)[2];
        double ut = (d - Math.floor(d)) * 24;
        double siderealTime = mod360(sunLongitude + 180 + ut * 15 + longitudeDeg);
        double hourAngle = siderealTime - geocentric[0];
        double g = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(geocentricLatitude)) / cos(hourAngle)));
        double ra = geocentric[0] - parallax * rho * cos(geocentricLatitude) * sin(hourAngle) / cos(geocentric[1]);
        double dec = Math.abs(sin(g)) < 1e-9
                ? geocentric[1] - parallax * rho * sin(-geocentric[1]) * cos(hourAngle)
                : geocentric[1] - parallax * rho * sin(geocentricLatitude) * sin(g - geocentric[1]) / sin(g);
        return new double[] {mod360(ra), dec, geocentric[2]};
    }

    /** Precesses equator-of-date coordinates back to J2000 by shifting the ecliptic longitude. */
    private static Position toJ2000(double raDeg, double decDeg, double d) {
        double obliquity = Math.toRadians(23.4393 - 3.563e-7 * d);
        double x = cos(decDeg) * cos(raDeg);
        double y = cos(decDeg) * sin(raDeg);
        double z = sin(decDeg);
        double ye = y * Math.cos(obliquity) + z * Math.sin(obliquity);
        double ze = -y * Math.sin(obliquity) + z * Math.cos(obliquity);
        double lon = Math.toDegrees(Math.atan2(ye, x)) - 3.82394e-5 * d;
        double lat = Math.toDegrees(Math.atan2(ze, Math.hypot(x, ye)));
        x = cos(lat) * cos(lon);
        y = cos(lat) * sin(lon);
        z = sin(lat);
        ye = y * Math.cos(obliquity) - z * Math.sin(obliquity);
        ze = y * Math.sin(obliquity) + z * Math.cos(obliquity);
        return new Position(mod360(Math.toDegrees(Math.atan2(ye, x))), Math.toDegrees(Math.atan2(ze, Math.hypot(x, ye))));
    }

    private static double sin(double degrees) {
        return Math.sin(Math.toRadians(degrees));
    }

    private static double cos(double degrees) {
        return Math.cos(Math.toRadians(degrees));
    }

    private static double mod360(double degrees) {
        double value = degrees % 360;
        return value < 0 ? value + 360 : value;
    }
}
