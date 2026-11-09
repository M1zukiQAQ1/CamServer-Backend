package edu.camserver.app.sky;

import java.util.List;

/**
 * What the sky package can say about one plate-solved frame: how clear the sky was and where to
 * draw constellations, planets and star names. All pixel coordinates are in the original image.
 */
public record SkyView(
        long imgId,
        Status status,
        String message,
        Fit fit,
        Quality quality,
        Overlay overlay
) {
    public enum Status {
        /** Quality and overlay are present. */
        OK,
        /** The frame has no plate solution (yet); start one with POST /api/plate-solve/{imgId}. */
        NOT_SOLVED,
        /** The identified stars do not fit a fisheye projection; the plate solution is doubtful. */
        FIT_FAILED
    }

    /** The fitted sky-to-pixel mapping: stars used, their RMS distance from the model, and its reach. */
    public record Fit(int stars, double rmsPx, double maxAngleDeg) {
    }

    /**
     * Share of the catalogue stars that should be visible and were detected.
     *
     * @param clearFraction      detected / expected for stars down to {@code magnitudeLimit} within
     *                           {@code maxAngleDeg} of the image centre; null with too few expected stars
     * @param limitingMagnitude  faint end of the faintest half-magnitude bin in which at least half
     *                           the stars were detected; null when even the brightest bin fails
     */
    public record Quality(
            Double clearFraction,
            int expected,
            int detected,
            double magnitudeLimit,
            double maxAngleDeg,
            Double limitingMagnitude,
            List<MagnitudeBin> bins
    ) {
    }

    public record MagnitudeBin(double from, double to, int expected, int detected) {
    }

    public record Overlay(List<Constellation> constellations, List<Body> bodies, List<Label> stars) {
    }

    /** Polylines in pixels; {@code label} is where to write the name, or null when that point is out of view. */
    public record Constellation(String id, String name, double[] label, List<List<double[]>> lines) {
    }

    /** A planet or the Moon. {@code detected}: the solver found a source at this position. */
    public record Body(String name, double x, double y, double raDeg, double decDeg, boolean detected) {
    }

    public record Label(String name, double x, double y, double magnitude) {
    }

    static SkyView unavailable(long imgId, Status status, String message) {
        return new SkyView(imgId, status, message, null, null, null);
    }
}
