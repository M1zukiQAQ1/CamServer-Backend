package edu.camserver.app.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Solar altitude (NOAA low-precision formulae, well under 0.1°) and the nights it defines. */
public final class SunPosition {

    public record Night(Instant start, Instant end) {
    }

    private static final Duration STEP = Duration.ofMinutes(5);

    private SunPosition() {
    }

    /** Geometric altitude of the Sun's centre in degrees (no refraction). */
    public static double altitude(double latitudeDeg, double longitudeDeg, Instant at) {
        double jd = at.toEpochMilli() / 86_400_000.0 + 2_440_587.5;
        double t = (jd - 2_451_545.0) / 36_525.0;
        double l0 = mod360(280.46646 + t * (36_000.76983 + t * 0.0003032));
        double m = Math.toRadians(357.52911 + t * (35_999.05029 - 0.0001537 * t));
        double c = Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t))
                + Math.sin(2 * m) * (0.019993 - 0.000101 * t) + Math.sin(3 * m) * 0.000289;
        double omega = Math.toRadians(125.04 - 1934.136 * t);
        double lambda = Math.toRadians(l0 + c - 0.00569 - 0.00478 * Math.sin(omega));
        double eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
        double eps = Math.toRadians(eps0 + 0.00256 * Math.cos(omega));
        double declination = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double rightAscension = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double d = jd - 2_451_545.0;
        double gmst = mod360(280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38_710_000.0);
        double hourAngle = Math.toRadians(gmst + longitudeDeg) - rightAscension;
        double latitude = Math.toRadians(latitudeDeg);
        return Math.toDegrees(Math.asin(Math.sin(latitude) * Math.sin(declination)
                + Math.cos(latitude) * Math.cos(declination) * Math.cos(hourAngle)));
    }

    /**
     * The most recent night (Sun below {@code sunAltitudeDeg}) that ended before {@code now}, to
     * the nearest five minutes. Empty only where the Sun never sets that far within two days.
     */
    public static Optional<Night> lastCompletedNight(double latitudeDeg, double longitudeDeg, double sunAltitudeDeg, Instant now) {
        Instant end = null;
        Instant t = now.minusSeconds(now.getEpochSecond() % STEP.getSeconds());
        boolean laterDark = altitude(latitudeDeg, longitudeDeg, t) < sunAltitudeDeg;
        for (int i = 0; i < 2 * 24 * 12; i++) {
            Instant earlier = t.minus(STEP);
            boolean dark = altitude(latitudeDeg, longitudeDeg, earlier) < sunAltitudeDeg;
            if (end == null && dark && !laterDark) {
                end = t;                   // darkness ended at t
            } else if (end != null && !dark && laterDark) {
                return Optional.of(new Night(t, end));  // and began at t
            }
            laterDark = dark;
            t = earlier;
        }
        return Optional.empty();
    }

    private static double mod360(double degrees) {
        double value = degrees % 360;
        return value < 0 ? value + 360 : value;
    }
}
