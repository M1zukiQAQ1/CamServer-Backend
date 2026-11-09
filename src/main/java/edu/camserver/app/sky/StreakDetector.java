package edu.camserver.app.sky;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Finds straight bright trails in one long exposure: meteors, satellites and aircraft.
 *
 * <p>Steps, on the picture reduced to half size: subtract the local sky (a box mean), keep pixels
 * clearly above the noise, join touching pixels into groups, and keep the groups that are long,
 * thin and straight. Stars are round, clouds and glare are wide, so neither passes. Collinear
 * pieces of one broken trail (an aircraft's strobe, a tumbling satellite) are then joined.
 *
 * <p>A single frame cannot tell a meteor from a satellite; the result only says where the trails
 * are and how long. Anything fixed in the field that is thin and bright (a lit mast, a wire, a
 * reflection in the dome) is reported in every frame.
 */
public final class StreakDetector {

    /** A trail from (x1, y1) to (x2, y2) in original-image pixels. {@code contrast}: mean excess over the sky, 0–255. */
    public record Streak(double x1, double y1, double x2, double y2, double lengthPx, double widthPx, double contrast) {
    }

    /**
     * @param minLengthPx  shortest trail reported, in original pixels
     * @param sigma        how far above the noise a pixel must be
     * @param minContrast  and how far above the local sky, in 8-bit counts
     * @param maxWidthPx   widest trail accepted, in original pixels
     * @param fieldRadius  only look inside this fraction of the half-height around the image centre
     *                     (the horizon ring holds buildings and lights); 0 or less = whole frame
     */
    public record Options(double minLengthPx, double sigma, double minContrast, double maxWidthPx, double fieldRadius) {
        public static Options defaults() {
            return new Options(60, 4.0, 5.0, 14, 0.8);
        }
    }

    private static final int SCALE = 2;
    private static final int BOX = 12;
    private static final int MAX_STREAKS = 20;

    private StreakDetector() {
    }

    public static List<Streak> detect(BufferedImage image, Options options) {
        int w = image.getWidth() / SCALE;
        int h = image.getHeight() / SCALE;
        if (w < 4 * BOX || h < 4 * BOX) {
            return List.of();
        }
        float[] gray = halfSizeGray(image, w, h);
        float[] residual = subtractLocalMean(gray, w, h);
        double noise = noise(residual);
        float threshold = (float) Math.max(options.sigma() * noise, options.minContrast());

        boolean[] mask = new boolean[w * h];
        double cx = w / 2.0;
        double cy = h / 2.0;
        double radius = options.fieldRadius() > 0 ? options.fieldRadius() * h / 2.0 : Double.MAX_VALUE;
        // Moonlight and lamps scatter in the dome into streaky glare; leave out sky much brighter than usual.
        float glare = glareLevel(gray, residual);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (residual[i] > threshold && gray[i] - residual[i] < glare && Math.hypot(x - cx, y - cy) <= radius) {
                    mask[i] = true;
                }
            }
        }

        List<Segment> segments = new ArrayList<>();
        int[] stack = new int[w * h];
        boolean[] seen = new boolean[w * h];
        int[] members = new int[w * h];
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) {
                continue;
            }
            int count = 0;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int index = stack[--top];
                members[count++] = index;
                int x = index % w;
                int y = index / w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
                            int next = ny * w + nx;
                            if (mask[next] && !seen[next]) {
                                seen[next] = true;
                                stack[top++] = next;
                            }
                        }
                    }
                }
            }
            if (count >= 6) {
                Segment segment = Segment.of(members, count, w, residual);
                if (segment != null) {
                    segments.add(segment);
                }
            }
        }

        double maxWidth = options.maxWidthPx() / SCALE;
        // Pieces of a broken trail are short, so anything elongated may be joined; only the result must be a line.
        List<Segment> elongated = segments.stream().filter(s -> s.isElongated(maxWidth)).toList();
        List<Segment> joined = joinCollinear(elongated);
        List<Streak> streaks = new ArrayList<>();
        for (Segment s : joined) {
            if (s.length * SCALE >= options.minLengthPx() && s.isLine(maxWidth) && s.length >= 6 * s.width) {
                streaks.add(new Streak(round(s.x1 * SCALE + 0.5), round(s.y1 * SCALE + 0.5), round(s.x2 * SCALE + 0.5),
                        round(s.y2 * SCALE + 0.5), round(s.length * SCALE), round(s.width * SCALE), round(s.contrast)));
            }
        }
        streaks.sort(Comparator.comparingDouble(Streak::lengthPx).reversed());
        return streaks.size() > MAX_STREAKS ? List.copyOf(streaks.subList(0, MAX_STREAKS)) : streaks;
    }

    /** A group of bright pixels summarised by its principal axis. Coordinates in half-size pixels. */
    private static final class Segment {
        double x1, y1, x2, y2;       // ends along the principal axis
        double length;               // end to end
        double width;                // 4 x RMS distance from the axis, at least 1
        double maxOff;               // farthest pixel from the axis
        double contrast;             // mean residual
        int pixels;
        double covered;              // summed length of the pieces this was joined from
        double ux, uy;               // unit vector of the axis

        static Segment of(int[] members, int count, int w, float[] residual) {
            double sx = 0, sy = 0, sum = 0;
            for (int i = 0; i < count; i++) {
                sx += members[i] % w;
                sy += members[i] / w;
                sum += residual[members[i]];
            }
            double mx = sx / count;
            double my = sy / count;
            double sxx = 0, syy = 0, sxy = 0;
            for (int i = 0; i < count; i++) {
                double dx = members[i] % w - mx;
                double dy = members[i] / w - my;
                sxx += dx * dx;
                syy += dy * dy;
                sxy += dx * dy;
            }
            double angle = 0.5 * Math.atan2(2 * sxy, sxx - syy);
            Segment s = new Segment();
            s.ux = Math.cos(angle);
            s.uy = Math.sin(angle);
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, off2 = 0;
            for (int i = 0; i < count; i++) {
                double dx = members[i] % w - mx;
                double dy = members[i] / w - my;
                double along = dx * s.ux + dy * s.uy;
                double across = -dx * s.uy + dy * s.ux;
                min = Math.min(min, along);
                max = Math.max(max, along);
                off2 += across * across;
                s.maxOff = Math.max(s.maxOff, Math.abs(across));
            }
            s.x1 = mx + min * s.ux;
            s.y1 = my + min * s.uy;
            s.x2 = mx + max * s.ux;
            s.y2 = my + max * s.uy;
            s.length = max - min + 1;
            s.width = Math.max(1, 4 * Math.sqrt(off2 / count));
            s.contrast = sum / count;
            s.pixels = count;
            s.covered = s.length;
            return s;
        }

        /** Thin, straight, and filled along its length (not two blobs with a gap, not an arc). */
        boolean isLine(double maxWidth) {
            return length >= 8 && width <= maxWidth && length >= 4 * width
                    && maxOff <= Math.max(2.5, 0.05 * length)
                    && pixels >= 0.5 * length && covered >= 0.4 * length;
        }

        /** A candidate piece of a trail: clearly longer than wide. */
        boolean isElongated(double maxWidth) {
            return length >= 6 && width <= maxWidth && length >= 2 * width && maxOff <= Math.max(2.5, 0.05 * length);
        }

        /** The segment spanning this one and another, when they lie on one line with a short gap between them. */
        Segment joinedWith(Segment other) {
            if (other.length > length) {
                return other.joinedWith(this);   // judge against the axis of the longer piece
            }
            // The direction of a short piece is poorly known: about 7 degrees for long ones, 20 for short.
            double cross = Math.abs(ux * other.uy - uy * other.ux);
            if (cross > (other.length >= 20 ? 0.12 : 0.35)) {
                return null;
            }
            double[][] ends = {{x1, y1}, {x2, y2}, {other.x1, other.y1}, {other.x2, other.y2}};
            // Direction between the two centres must agree with both axes.
            double cxa = (x1 + x2) / 2, cya = (y1 + y2) / 2, cxb = (other.x1 + other.x2) / 2, cyb = (other.y1 + other.y2) / 2;
            double dx = cxb - cxa, dy = cyb - cya;
            double distance = Math.hypot(dx, dy);
            if (distance < 1e-6) {
                return null;
            }
            double off = Math.abs(-dx * uy + dy * ux);
            if (off > Math.max(2.5, 0.04 * distance)) {
                return null;
            }
            double gap = distance - (length + other.length) / 2;
            if (gap > 1.5 * other.length + 4) {   // other is the shorter piece
                return null;
            }
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            double[] first = null, last = null;
            for (double[] end : ends) {
                double along = (end[0] - cxa) * ux + (end[1] - cya) * uy;
                if (along < min) {
                    min = along;
                    first = end;
                }
                if (along > max) {
                    max = along;
                    last = end;
                }
            }
            Segment s = new Segment();
            s.x1 = first[0];
            s.y1 = first[1];
            s.x2 = last[0];
            s.y2 = last[1];
            s.length = Math.hypot(s.x2 - s.x1, s.y2 - s.y1) + 1;
            s.ux = (s.x2 - s.x1) / Math.max(1e-9, s.length - 1);
            s.uy = (s.y2 - s.y1) / Math.max(1e-9, s.length - 1);
            s.width = Math.max(width, other.width);
            s.maxOff = Math.max(Math.max(maxOff, other.maxOff), off);
            s.pixels = pixels + other.pixels;
            s.covered = covered + other.covered;
            s.contrast = (contrast * pixels + other.contrast * other.pixels) / s.pixels;
            return s;
        }
    }

    private static List<Segment> joinCollinear(List<Segment> input) {
        List<Segment> segments = new ArrayList<>(input);
        boolean merged = true;
        while (merged && segments.size() > 1) {
            merged = false;
            outer:
            for (int i = 0; i < segments.size(); i++) {
                for (int j = i + 1; j < segments.size(); j++) {
                    Segment joined = segments.get(i).joinedWith(segments.get(j));
                    if (joined != null) {
                        segments.set(i, joined);
                        segments.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        return segments;
    }

    // ---- pixels -----------------------------------------------------------------------------

    private static float[] halfSizeGray(BufferedImage image, int w, int h) {
        int fullWidth = image.getWidth();
        float[] gray = new float[w * h];
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = 2 * y * fullWidth + 2 * x;
                    gray[y * w + x] = ((data[i] & 255) + (data[i + 1] & 255) + (data[i + fullWidth] & 255)
                            + (data[i + fullWidth + 1] & 255)) / 4f;
                }
            }
            return gray;
        }
        int[] row = new int[2 * fullWidth];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, 2 * y, fullWidth, 2, row, 0, fullWidth);
            for (int x = 0; x < w; x++) {
                float sum = 0;
                for (int k : new int[] {2 * x, 2 * x + 1, fullWidth + 2 * x, fullWidth + 2 * x + 1}) {
                    int rgb = row[k];
                    sum += 0.299f * ((rgb >> 16) & 255) + 0.587f * ((rgb >> 8) & 255) + 0.114f * (rgb & 255);
                }
                gray[y * w + x] = sum / 4;
            }
        }
        return gray;
    }

    /** Each pixel minus the mean of the (2·BOX+1)² box around it, through a summed-area table. */
    private static float[] subtractLocalMean(float[] gray, int w, int h) {
        double[] integral = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += gray[y * w + x];
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + rowSum;
            }
        }
        float[] residual = new float[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - BOX);
            int y1 = Math.min(h, y + BOX + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - BOX);
                int x1 = Math.min(w, x + BOX + 1);
                double sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1]
                        - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0];
                residual[y * w + x] = (float) (gray[y * w + x] - sum / ((y1 - y0) * (x1 - x0)));
            }
        }
        return residual;
    }

    /** Local sky level above which a region counts as glare: well above the typical (median) sky of the frame. */
    private static float glareLevel(float[] gray, float[] residual) {
        float[] sample = new float[gray.length / 7 + 1];
        int n = 0;
        for (int i = 0; i < gray.length; i += 7) {
            sample[n++] = gray[i] - residual[i];
        }
        Arrays.sort(sample, 0, n);
        float median = sample[n / 2];
        return Math.max(2.5f * median, median + 40);
    }

    /** Robust standard deviation of the residual (1.4826 x median absolute value), from every 7th pixel. */
    private static double noise(float[] residual) {
        float[] sample = new float[residual.length / 7 + 1];
        int n = 0;
        for (int i = 0; i < residual.length; i += 7) {
            sample[n++] = Math.abs(residual[i]);
        }
        Arrays.sort(sample, 0, n);
        return Math.max(0.5, 1.4826 * sample[n / 2]);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
