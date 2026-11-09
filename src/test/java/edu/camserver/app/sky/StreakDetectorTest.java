package edu.camserver.app.sky;

import edu.camserver.app.sky.StreakDetector.Options;
import edu.camserver.app.sky.StreakDetector.Streak;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Trails are drawn into a real night frame from the Broida roof (frame 57316, 2500 x 2180). */
class StreakDetectorTest {

    private static BufferedImage nightFrame() throws IOException {
        try (InputStream in = StreakDetectorTest.class.getResourceAsStream("/lost/frame-57316.jpg")) {
            BufferedImage image = ImageIO.read(in);
            assertThat(image.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
            return image;
        }
    }

    /** Adds a soft trail about 3 px wide, {@code brightness} counts above the sky; {@code dash} > 0 breaks it up. */
    private static void drawTrail(BufferedImage image, double x1, double y1, double x2, double y2, int brightness, int dash) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        for (double t = 0; t <= length; t += 0.5) {
            if (dash > 0 && ((int) (t / dash)) % 2 == 1) {
                continue;
            }
            double x = x1 + (x2 - x1) * t / length;
            double y = y1 + (y2 - y1) * t / length;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int px = (int) Math.round(x) + dx;
                    int py = (int) Math.round(y) + dy;
                    double weight = Math.exp(-(Math.pow(px - x, 2) + Math.pow(py - y, 2)) / 2.0);
                    int[] pixel = image.getRaster().getPixel(px, py, (int[]) null);
                    // Each pixel is visited several times along the trail: keep the brightest contribution.
                    int value = Math.max(pixel[0], Math.min(255, base(image, px, py) + (int) Math.round(brightness * weight)));
                    image.getRaster().setPixel(px, py, new int[] {value});
                }
            }
        }
    }

    private static BufferedImage original;

    private static int base(BufferedImage image, int x, int y) {
        return original.getRaster().getPixel(x, y, (int[]) null)[0];
    }

    private static BufferedImage frameWith(double x1, double y1, double x2, double y2, int brightness, int dash) throws IOException {
        original = nightFrame();
        BufferedImage image = nightFrame();
        drawTrail(image, x1, y1, x2, y2, brightness, dash);
        return image;
    }

    private static void assertEnds(Streak streak, double x1, double y1, double x2, double y2) {
        boolean sameWay = Math.hypot(streak.x1() - x1, streak.y1() - y1) < Math.hypot(streak.x1() - x2, streak.y1() - y2);
        double[] a = sameWay ? new double[] {x1, y1, x2, y2} : new double[] {x2, y2, x1, y1};
        assertThat(Math.hypot(streak.x1() - a[0], streak.y1() - a[1])).isLessThan(12);
        assertThat(Math.hypot(streak.x2() - a[2], streak.y2() - a[3])).isLessThan(12);
    }

    @Test
    void aStarryFrameHasNoTrails() throws IOException {
        assertThat(StreakDetector.detect(nightFrame(), Options.defaults())).isEmpty();
    }

    @Test
    void findsAFaintTrailAndItsEnds() throws IOException {
        List<Streak> streaks = StreakDetector.detect(frameWith(900, 700, 1250, 880, 14, 0), Options.defaults());
        assertThat(streaks).hasSize(1);
        assertEnds(streaks.get(0), 900, 700, 1250, 880);
        assertThat(streaks.get(0).lengthPx()).isCloseTo(Math.hypot(350, 180), within(20.0));
        assertThat(streaks.get(0).widthPx()).isLessThan(12);
    }

    @Test
    void joinsTheDashesOfAStrobingAircraft() throws IOException {
        List<Streak> streaks = StreakDetector.detect(frameWith(700, 800, 700, 1300, 30, 30), Options.defaults());
        assertThat(streaks).hasSize(1);
        assertEnds(streaks.get(0), 700, 800, 700, 1300);
    }

    @Test
    void leavesTheMoonsGlareAlone() throws IOException {
        // The Moon is near (1530, 640) in this frame; a trail through its glare is not reported, and neither is the glare.
        assertThat(StreakDetector.detect(frameWith(1500, 600, 1500, 760, 30, 0), Options.defaults())).isEmpty();
    }

    @Test
    void ignoresAShortMarkAndTheHorizonRing() throws IOException {
        // 30 px: below the 60 px minimum
        assertThat(StreakDetector.detect(frameWith(1200, 1000, 1226, 1015, 40, 0), Options.defaults())).isEmpty();
        // 1,000 px from the centre: outside the default field, found when the whole frame is searched
        BufferedImage edge = frameWith(1150, 60, 1400, 110, 40, 0);
        assertThat(StreakDetector.detect(edge, Options.defaults())).isEmpty();
        assertThat(StreakDetector.detect(edge, new Options(60, 4, 5, 14, 0))).isNotEmpty();
    }

    @Test
    void readsColourFramesToo() throws IOException {
        BufferedImage gray = frameWith(900, 700, 1250, 880, 20, 0);
        BufferedImage rgb = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(gray, 0, 0, null);
        List<Streak> streaks = StreakDetector.detect(rgb, Options.defaults());
        assertThat(streaks).hasSize(1);
        assertEnds(streaks.get(0), 900, 700, 1250, 880);
    }
}
