package edu.camserver.app.sky;

import edu.camserver.app.model.Image;
import edu.camserver.app.model.platesolve.PlateSolveResult;
import edu.camserver.app.model.platesolve.PlateSolveStar;
import edu.camserver.app.model.platesolve.PlateSolveStatus;
import edu.camserver.app.service.ImageService;
import edu.camserver.app.service.PlateSolveService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives a {@link SkyView} from a frame's plate solution. It never starts a solve: a frame
 * without one reports {@link SkyView.Status#NOT_SOLVED}.
 *
 * <p><b>Sky quality.</b> The fitted projection says where every catalogue star should be in the
 * picture. A star counts as detected when the solver found a source within a few pixels of that
 * place, whether or not it identified it. On a clear night every star to magnitude 3.5 within 55°
 * of the image centre is detected (60 of 60 on frame 85123), so the detected share is the clear
 * share of that part of the sky. Cloud, haze, moonlight glare and dew on the dome all lower it;
 * the number does not tell them apart.
 */
@Service
public class SkyService {
    private static final int CACHE_SIZE = 200;
    private static final double BIN_WIDTH = 0.5;
    private static final double FAINTEST_BIN = 5.0;

    private final PlateSolveService plateSolveService;
    private final ImageService imageService;
    private final SkyCatalog catalog;
    private final double magnitudeLimit;
    private final double qualityAngleDeg;
    private final double matchPx;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Instant solvedAt, SkyView view) {
    }

    public SkyService(
            PlateSolveService plateSolveService,
            ImageService imageService,
            SkyCatalog catalog,
            @Value("${app.sky.quality.magnitude-limit:3.5}") double magnitudeLimit,
            @Value("${app.sky.quality.max-angle-deg:55}") double qualityAngleDeg,
            @Value("${app.sky.quality.match-px:4}") double matchPx) {
        this.plateSolveService = plateSolveService;
        this.imageService = imageService;
        this.catalog = catalog;
        this.magnitudeLimit = magnitudeLimit;
        this.qualityAngleDeg = qualityAngleDeg;
        this.matchPx = matchPx;
    }

    public SkyView view(long imgId) {
        PlateSolveResult result = plateSolveService.getStatus(imgId);
        if (result.status() != PlateSolveStatus.SOLVED || result.stars() == null || result.crop() == null) {
            return SkyView.unavailable(imgId, SkyView.Status.NOT_SOLVED,
                    result.status() == PlateSolveStatus.SOLVED ? "The plate solution has no stars." : result.message());
        }
        Cached cached = cache.get(imgId);
        if (cached != null && cached.solvedAt().equals(result.generatedAt())) {
            return cached.view();
        }
        Image image = imageService.findById(imgId);
        SkyView view = build(result, image.getTimestamp());
        if (cache.size() >= CACHE_SIZE) {
            cache.clear();
        }
        if (result.generatedAt() != null) {
            cache.put(imgId, new Cached(result.generatedAt(), view));
        }
        return view;
    }

    /** The view of one solved frame captured at {@code capturedAt}. */
    SkyView build(PlateSolveResult result, Instant capturedAt) {
        int width = result.crop().originalWidth();
        int height = result.crop().originalHeight();
        List<SkyProjection.Match> matches = new ArrayList<>();
        for (PlateSolveStar star : result.stars()) {
            if (star.name() != null && star.raDeg() != null && star.decDeg() != null) {
                matches.add(new SkyProjection.Match(star.raDeg(), star.decDeg(), star.x(), star.y()));
            }
        }
        Optional<SkyProjection> fitted = SkyProjection.fit(matches, width, height);
        if (fitted.isEmpty()) {
            return SkyView.unavailable(result.imgId(), SkyView.Status.FIT_FAILED,
                    matches.size() + " identified stars do not fit a fisheye projection.");
        }
        SkyProjection projection = fitted.get();
        Frame frame = new Frame(projection, width, height, result.stars());
        Double latitude = result.solution() == null ? null : result.solution().siteLatitudeDeg();
        Double longitude = result.solution() == null ? null : result.solution().siteLongitudeDeg();
        return new SkyView(result.imgId(), SkyView.Status.OK, null,
                new SkyView.Fit(projection.stars(), round(projection.rmsPx(), 3), round(projection.maxAngleDeg(), 2)),
                quality(frame),
                overlay(frame, capturedAt, latitude == null ? 0 : latitude, longitude == null ? 0 : longitude));
    }

    // ---- sky quality ------------------------------------------------------------------------

    private SkyView.Quality quality(Frame frame) {
        double maxAngle = Math.min(qualityAngleDeg, frame.projection.maxAngleDeg());
        int bins = (int) Math.round(FAINTEST_BIN / BIN_WIDTH) + 1;   // the first bin holds everything brighter than magnitude 0
        int[] expected = new int[bins];
        int[] detected = new int[bins];
        int expectedBright = 0;
        int detectedBright = 0;
        for (SkyCatalog.Star star : catalog.stars()) {
            if (star.magnitude() > FAINTEST_BIN || frame.projection.angleFromAxisDeg(star.raDeg(), star.decDeg()) > maxAngle) {
                continue;
            }
            double[] xy = frame.projection.project(star.raDeg(), star.decDeg());
            if (!frame.inside(xy)) {
                continue;
            }
            boolean found = frame.hasDetectionNear(xy, matchPx);
            int bin = Math.max(0, Math.min(bins - 1, (int) Math.ceil(star.magnitude() / BIN_WIDTH - 1e-9)));
            expected[bin]++;
            if (found) {
                detected[bin]++;
            }
            if (star.magnitude() <= magnitudeLimit) {
                expectedBright++;
                if (found) {
                    detectedBright++;
                }
            }
        }
        List<SkyView.MagnitudeBin> list = new ArrayList<>();
        Double limiting = null;
        boolean unbroken = true;
        for (int bin = 0; bin < bins; bin++) {
            double to = bin * BIN_WIDTH;
            list.add(new SkyView.MagnitudeBin(bin == 0 ? -2 : to - BIN_WIDTH, to, expected[bin], detected[bin]));
            if (expected[bin] >= 5 && unbroken) {
                if (2 * detected[bin] >= expected[bin]) {
                    limiting = to;
                } else {
                    unbroken = false;
                }
            }
        }
        Double clear = expectedBright < 10 ? null : round((double) detectedBright / expectedBright, 3);
        return new SkyView.Quality(clear, expectedBright, detectedBright, magnitudeLimit, round(maxAngle, 1), limiting, list);
    }

    // ---- overlay ----------------------------------------------------------------------------

    private SkyView.Overlay overlay(Frame frame, Instant capturedAt, double latitudeDeg, double longitudeDeg) {
        // The polynomial is only pinned down where there were stars; allow a little beyond that.
        double limit = Math.min(frame.projection.maxAngleDeg() + 8, 95);

        List<SkyView.Constellation> constellations = new ArrayList<>();
        for (SkyCatalog.Figure figure : catalog.figures()) {
            List<List<double[]>> lines = new ArrayList<>();
            for (List<double[]> line : figure.lines()) {
                lines.addAll(visibleParts(frame, line, limit));
            }
            if (!lines.isEmpty()) {
                double[] label = visible(frame, figure.label()[0], figure.label()[1], limit);
                constellations.add(new SkyView.Constellation(figure.id(), figure.name(), label, lines));
            }
        }

        List<SkyView.Body> bodies = new ArrayList<>();
        for (Ephemeris.Body body : Ephemeris.Body.values()) {
            if (body == Ephemeris.Body.SUN) {
                continue;
            }
            Ephemeris.Position position = Ephemeris.position(body, capturedAt, latitudeDeg, longitudeDeg);
            double[] xy = visible(frame, position.raDeg(), position.decDeg(), limit);
            if (xy != null) {
                bodies.add(new SkyView.Body(body.label(), xy[0], xy[1], round(position.raDeg(), 4), round(position.decDeg(), 4),
                        frame.hasDetectionNear(xy, 1.5 * matchPx)));
            }
        }

        List<SkyView.Label> labels = new ArrayList<>();
        for (SkyCatalog.Star star : catalog.namedStars()) {
            double[] xy = visible(frame, star.raDeg(), star.decDeg(), limit);
            if (xy != null && star.name() != null && !star.name().isBlank()) {
                labels.add(new SkyView.Label(star.name(), xy[0], xy[1], star.magnitude()));
            }
        }
        return new SkyView.Overlay(constellations, bodies, labels);
    }

    /** Pixel position rounded to 0.1 px, or null when the direction is beyond the limit or off the picture. */
    private static double[] visible(Frame frame, double raDeg, double decDeg, double limitDeg) {
        if (frame.projection.angleFromAxisDeg(raDeg, decDeg) > limitDeg) {
            return null;
        }
        double[] xy = frame.projection.project(raDeg, decDeg);
        return frame.inside(xy) ? new double[] {round(xy[0], 1), round(xy[1], 1)} : null;
    }

    /**
     * A constellation line follows great circles between its stars; through a fisheye those are
     * curves, so each segment is walked in steps of at most 2° and cut where it leaves the view.
     */
    private static List<List<double[]>> visibleParts(Frame frame, List<double[]> line, double limitDeg) {
        List<List<double[]>> parts = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        for (int i = 0; i < line.size(); i++) {
            double[] to = SkyProjection.unit(Math.toRadians(line.get(i)[0]), Math.toRadians(line.get(i)[1]));
            int steps = 1;
            double[] from = to;
            double angle = 0;
            if (i > 0) {
                from = SkyProjection.unit(Math.toRadians(line.get(i - 1)[0]), Math.toRadians(line.get(i - 1)[1]));
                angle = Math.acos(Math.max(-1, Math.min(1, SkyProjection.dot(from, to))));
                steps = Math.max(1, (int) Math.ceil(Math.toDegrees(angle) / 2));
            }
            for (int step = 1; step <= steps; step++) {
                double t = (double) step / steps;
                double[] v = to;
                if (step < steps) {
                    double a = Math.sin((1 - t) * angle) / Math.sin(angle);
                    double b = Math.sin(t * angle) / Math.sin(angle);
                    v = new double[] {a * from[0] + b * to[0], a * from[1] + b * to[1], a * from[2] + b * to[2]};
                }
                double ra = Math.toDegrees(Math.atan2(v[1], v[0]));
                double dec = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, v[2]))));
                double[] xy = visible(frame, ra, dec, limitDeg);
                if (xy != null) {
                    current.add(xy);
                } else {
                    if (current.size() > 1) {
                        parts.add(current);
                    }
                    current = new ArrayList<>();
                }
            }
        }
        if (current.size() > 1) {
            parts.add(current);
        }
        return parts;
    }

    /** One frame: its projection, its size, and every source the solver detected. */
    private record Frame(SkyProjection projection, int width, int height, List<PlateSolveStar> detections) {
        boolean inside(double[] xy) {
            return xy[0] >= 0 && xy[1] >= 0 && xy[0] < width && xy[1] < height;
        }

        boolean hasDetectionNear(double[] xy, double radiusPx) {
            for (PlateSolveStar star : detections) {
                if (Math.hypot(star.x() - xy[0], star.y() - xy[1]) <= radiusPx) {
                    return true;
                }
            }
            return false;
        }
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }
}
