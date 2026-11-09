package edu.camserver.app.sky;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Sky-to-pixel mapping of one all-sky frame, fitted to the stars its plate solution identified.
 *
 * <p>The model is a fisheye pointing at {@code (axisRa, axisDec)}: a direction at angle θ from the
 * axis lands {@code k1·θ + k3·θ³ + k5·θ⁵} pixels from {@code (cx, cy)}, at the position angle around
 * the axis plus a roll, mirrored or not. Eight numbers and a parity, found by least squares. It
 * needs neither the capture time nor the site, only (RA, Dec, x, y) pairs, so it can place
 * anything with known coordinates on the picture: undetected stars, planets, constellation lines.
 */
public final class SkyProjection {

    /** One identified star: where it is in the sky and where it is in the picture. */
    public record Match(double raDeg, double decDeg, double x, double y) {
    }

    private static final int MIN_STARS = 12;
    private static final double MAX_RMS_PX = 3.0;

    private final double[] p;       // axisRa, axisDec, roll (rad), cx, cy, k1, k3, k5
    private final int parity;
    private final double rmsPx;
    private final double maxAngleRad;
    private final int stars;
    // Unit vectors of the camera frame, derived from p
    private final double[] axis;
    private final double[] east;
    private final double[] north;

    private SkyProjection(double[] p, int parity, double rmsPx, double maxAngleRad, int stars) {
        this.p = p;
        this.parity = parity;
        this.rmsPx = rmsPx;
        this.maxAngleRad = maxAngleRad;
        this.stars = stars;
        this.axis = unit(p[0], p[1]);
        this.east = new double[] {-Math.sin(p[0]), Math.cos(p[0]), 0};
        this.north = cross(axis, east);
    }

    /** Root-mean-square distance in pixels between the fitted stars and where the model puts them. */
    public double rmsPx() {
        return rmsPx;
    }

    /** Largest angle from the optical axis among the fitted stars, in degrees. */
    public double maxAngleDeg() {
        return Math.toDegrees(maxAngleRad);
    }

    /** Number of stars the final fit used. */
    public int stars() {
        return stars;
    }

    /** Angle between a sky position and the optical axis, in degrees. */
    public double angleFromAxisDeg(double raDeg, double decDeg) {
        return Math.toDegrees(Math.acos(clamp(dot(unit(Math.toRadians(raDeg), Math.toRadians(decDeg)), axis))));
    }

    /** Pixel position of a sky position. Meaningful up to a few degrees beyond {@link #maxAngleDeg()}. */
    public double[] project(double raDeg, double decDeg) {
        return project(unit(Math.toRadians(raDeg), Math.toRadians(decDeg)));
    }

    double[] project(double[] v) {
        return model(p, parity, v, axis, east, north);
    }

    /**
     * Fits the model. Empty when there are too few stars or they do not agree with a fisheye to
     * within a few pixels (a wrong plate solution).
     */
    public static Optional<SkyProjection> fit(List<Match> matches, int width, int height) {
        if (matches.size() < MIN_STARS) {
            return Optional.empty();
        }
        List<Match> used = new ArrayList<>(matches);
        Candidate best = bestStart(used, width, height);
        if (best == null) {
            return Optional.empty();
        }
        // Fit, keep the stars that agree, fit again. Every round judges all the stars, so one that
        // was pushed out by a bad neighbour in the first fit comes back in the next.
        for (int round = 0; round < 3; round++) {
            double[] residuals = residuals(best.p, best.parity, matches);
            double limit = Math.max(2.0, 4 * median(residuals));
            List<Match> kept = new ArrayList<>();
            for (int i = 0; i < matches.size(); i++) {
                if (residuals[i] <= limit) {
                    kept.add(matches.get(i));
                }
            }
            if (kept.size() < MIN_STARS) {
                return Optional.empty();
            }
            boolean unchanged = kept.equals(used);
            used = kept;
            if (unchanged && round > 0) {
                break;
            }
            best = new Candidate(refine(best.p, best.parity, used, 80), best.parity);
        }
        double[] residuals = residuals(best.p, best.parity, used);
        double sum = 0;
        for (double r : residuals) {
            sum += r * r;
        }
        double rms = Math.sqrt(sum / residuals.length);
        if (!(rms <= MAX_RMS_PX) || best.p[5] <= 0) {
            return Optional.empty();
        }
        double[] axis = unit(best.p[0], best.p[1]);
        double maxAngle = 0;
        for (Match m : used) {
            maxAngle = Math.max(maxAngle, Math.acos(clamp(dot(unit(Math.toRadians(m.raDeg()), Math.toRadians(m.decDeg())), axis))));
        }
        return Optional.of(new SkyProjection(best.p, best.parity, rms, maxAngle, used.size()));
    }

    // ---- fitting --------------------------------------------------------------------------

    private record Candidate(double[] p, int parity) {
    }

    /** Tries both parities and every roll in 5° steps, refines the three most promising, keeps the best. */
    private static Candidate bestStart(List<Match> matches, int width, int height) {
        double cx = width / 2.0;
        double cy = height / 2.0;
        Match centre = matches.stream()
                .min(Comparator.comparingDouble(m -> Math.hypot(m.x() - cx, m.y() - cy))).orElseThrow();
        double ra0 = Math.toRadians(centre.raDeg());
        double dec0 = Math.toRadians(centre.decDeg());
        double[] axis = unit(ra0, dec0);
        List<Double> scales = new ArrayList<>();
        for (Match m : matches) {
            double angle = Math.acos(clamp(dot(unit(Math.toRadians(m.raDeg()), Math.toRadians(m.decDeg())), axis)));
            if (angle > Math.toRadians(5)) {
                scales.add(Math.hypot(m.x() - centre.x(), m.y() - centre.y()) / angle);
            }
        }
        if (scales.isEmpty()) {
            return null;
        }
        double k1 = median(scales.stream().mapToDouble(Double::doubleValue).toArray());

        record Start(double[] p, int parity, double cost) {
        }
        List<Start> starts = new ArrayList<>();
        for (int parity = -1; parity <= 1; parity += 2) {
            for (int rollDeg = 0; rollDeg < 360; rollDeg += 5) {
                double[] start = {ra0, dec0, Math.toRadians(rollDeg), centre.x(), centre.y(), k1, 0, 0};
                starts.add(new Start(start, parity, median(residuals(start, parity, matches))));
            }
        }
        starts.sort(Comparator.comparingDouble(Start::cost));
        Candidate best = null;
        double bestCost = Double.MAX_VALUE;
        for (Start start : starts.subList(0, 3)) {
            double[] refined = refine(start.p(), start.parity(), matches, 60);
            double cost = median(residuals(refined, start.parity(), matches));
            if (cost < bestCost) {
                bestCost = cost;
                best = new Candidate(refined, start.parity());
            }
        }
        return best;
    }

    /** Levenberg–Marquardt on the pixel residuals, with a numerical Jacobian. */
    private static double[] refine(double[] start, int parity, List<Match> matches, int iterations) {
        int n = start.length;
        double[] p = start.clone();
        double[] r = residualVector(p, parity, matches);
        double cost = dot(r, r);
        double lambda = 1e-3;
        for (int iteration = 0; iteration < iterations; iteration++) {
            double[][] jacobian = new double[n][];
            for (int k = 0; k < n; k++) {
                double h = 1e-6 * Math.max(1, Math.abs(p[k]));
                double[] plus = p.clone();
                double[] minus = p.clone();
                plus[k] += h;
                minus[k] -= h;
                double[] rp = residualVector(plus, parity, matches);
                double[] rm = residualVector(minus, parity, matches);
                double[] column = new double[r.length];
                for (int i = 0; i < r.length; i++) {
                    column[i] = (rp[i] - rm[i]) / (2 * h);
                }
                jacobian[k] = column;
            }
            double[][] jtj = new double[n][n];
            double[] jtr = new double[n];
            for (int a = 0; a < n; a++) {
                jtr[a] = dot(jacobian[a], r);
                for (int b = a; b < n; b++) {
                    jtj[a][b] = jtj[b][a] = dot(jacobian[a], jacobian[b]);
                }
            }
            boolean improved = false;
            for (int attempt = 0; attempt < 12 && !improved; attempt++) {
                double[][] a = new double[n][n + 1];
                for (int i = 0; i < n; i++) {
                    System.arraycopy(jtj[i], 0, a[i], 0, n);
                    a[i][i] += lambda * Math.max(jtj[i][i], 1e-12);
                    a[i][n] = -jtr[i];
                }
                double[] step = solve(a);
                if (step != null) {
                    double[] next = p.clone();
                    for (int i = 0; i < n; i++) {
                        next[i] += step[i];
                    }
                    double[] nextR = residualVector(next, parity, matches);
                    double nextCost = dot(nextR, nextR);
                    if (Double.isFinite(nextCost) && nextCost < cost) {
                        boolean converged = cost - nextCost < 1e-10 * cost;
                        p = next;
                        r = nextR;
                        cost = nextCost;
                        lambda = Math.max(lambda / 10, 1e-12);
                        improved = true;
                        if (converged) {
                            return p;
                        }
                        continue;
                    }
                }
                lambda *= 10;
            }
            if (!improved) {
                break;
            }
        }
        return p;
    }

    /** Gaussian elimination with partial pivoting on an augmented matrix; null when singular. */
    private static double[] solve(double[][] a) {
        int n = a.length;
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-300) {
                return null;
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            for (int row = col + 1; row < n; row++) {
                double factor = a[row][col] / a[col][col];
                for (int k = col; k <= n; k++) {
                    a[row][k] -= factor * a[col][k];
                }
            }
        }
        double[] x = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double sum = a[row][n];
            for (int k = row + 1; k < n; k++) {
                sum -= a[row][k] * x[k];
            }
            x[row] = sum / a[row][row];
        }
        return x;
    }

    private static double[] residualVector(double[] p, int parity, List<Match> matches) {
        double[] axis = unit(p[0], p[1]);
        double[] east = {-Math.sin(p[0]), Math.cos(p[0]), 0};
        double[] north = cross(axis, east);
        double[] r = new double[2 * matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            double[] xy = model(p, parity, unit(Math.toRadians(m.raDeg()), Math.toRadians(m.decDeg())), axis, east, north);
            r[2 * i] = xy[0] - m.x();
            r[2 * i + 1] = xy[1] - m.y();
        }
        return r;
    }

    private static double[] residuals(double[] p, int parity, List<Match> matches) {
        double[] r = residualVector(p, parity, matches);
        double[] distance = new double[matches.size()];
        for (int i = 0; i < distance.length; i++) {
            distance[i] = Math.hypot(r[2 * i], r[2 * i + 1]);
        }
        return distance;
    }

    private static double[] model(double[] p, int parity, double[] v, double[] axis, double[] east, double[] north) {
        double theta = Math.acos(clamp(dot(v, axis)));
        double phi = Math.atan2(dot(v, north), dot(v, east)) + p[2];
        double t2 = theta * theta;
        double radius = theta * (p[5] + t2 * (p[6] + t2 * p[7]));
        return new double[] {p[3] + radius * Math.cos(phi), p[4] + parity * radius * Math.sin(phi)};
    }

    // ---- small vector helpers -------------------------------------------------------------

    static double[] unit(double raRad, double decRad) {
        return new double[] {Math.cos(decRad) * Math.cos(raRad), Math.cos(decRad) * Math.sin(raRad), Math.sin(decRad)};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static double clamp(double cosine) {
        return Math.max(-1, Math.min(1, cosine));
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n == 0 ? Double.NaN : n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }
}
