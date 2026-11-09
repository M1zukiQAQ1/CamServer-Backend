package edu.camserver.app.sky;

import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.Image;
import edu.camserver.app.service.ImageService;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Runs {@link StreakDetector} on a stored frame's JPEG. Night frames only: by day clouds are the brightest thin things. */
@Service
public class StreakService {

    public enum Status {
        OK, DAYTIME, NO_IMAGE
    }

    public record StreakView(long imgId, Status status, String message, int width, int height, List<StreakDetector.Streak> streaks) {
    }

    private static final int CACHE_SIZE = 200;
    private static final int MIN_EXPOSURE_US = 5_000_000;
    private final ImageService imageService;
    private final ImagePaths imagePaths;
    private final Map<Long, StreakView> cache = new ConcurrentHashMap<>();

    public StreakService(ImageService imageService, ImagePaths imagePaths) {
        this.imageService = imageService;
        this.imagePaths = imagePaths;
    }

    public StreakView view(long imgId) {
        StreakView cached = cache.get(imgId);
        if (cached != null) {
            return cached;
        }
        Image image = imageService.findById(imgId);
        // The day flag some cameras upload is unreliable (Broida sends night at noon), so the exposure
        // decides as well. Twilight frames (about 1 s at Broida) are left out too: in their bright,
        // uneven sky the edge of a dome reflection passed for a trail (frame 85152). Dark-sky
        // exposures there are 13 s and longer.
        if (Boolean.TRUE.equals(image.getIsDayTime()) || image.getExposure() < MIN_EXPOSURE_US) {
            return new StreakView(imgId, Status.DAYTIME, "Trails are only looked for in dark-sky exposures of 5 s or more.", 0, 0, List.of());
        }
        Optional<Path> jpeg = imagePaths.jpegOf(image.getImgPath());
        BufferedImage picture = null;
        if (jpeg.isPresent()) {
            try {
                picture = ImageIO.read(jpeg.get().toFile());
            } catch (IOException ignored) {
                // reported as NO_IMAGE below
            }
        }
        if (picture == null) {
            return new StreakView(imgId, Status.NO_IMAGE, "The frame's JPEG is not available.", 0, 0, List.of());
        }
        StreakView view = new StreakView(imgId, Status.OK, null, picture.getWidth(), picture.getHeight(),
                StreakDetector.detect(picture, StreakDetector.Options.defaults()));
        if (cache.size() >= CACHE_SIZE) {
            cache.clear();
        }
        cache.put(imgId, view);
        return view;
    }
}
