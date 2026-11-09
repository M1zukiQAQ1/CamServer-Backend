package edu.camserver.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.Camera;
import edu.camserver.app.model.Image;
import edu.camserver.app.repository.CameraRepository;
import edu.camserver.app.repository.ImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One summary per camera per night: a keogram and a time-lapse video.
 *
 * <p>A night runs while the Sun is more than {@code app.nightly.sun-altitude-deg} below the
 * horizon at the camera and is named after the local date of its evening. The keogram puts the
 * centre column of every frame side by side, with time running left to right in proportion, so a
 * gap in the uploads stays a black gap. The video plays the frames at {@code app.nightly.fps}; it
 * needs ffmpeg on the server and is simply left out when ffmpeg is missing or fails.
 *
 * <p>Results live in {@code app.nightly.dir/<cameraId>/<date>/}: {@code night.json},
 * {@code keogram.jpg}, {@code timelapse.mp4}. Every hour the last completed night of each camera
 * is built if it is not there yet.
 */
@Service
public class NightlyService {
    private static final Logger log = LoggerFactory.getLogger(NightlyService.class);
    private static final Pattern CAMERA_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    public static final String KEOGRAM = "keogram.jpg";
    public static final String TIMELAPSE = "timelapse.mp4";
    private static final String SUMMARY = "night.json";

    public record FrameRef(long imgId, Instant at) {
    }

    /**
     * @param date           local date of the evening the night began
     * @param timelapse      file name, or null when no video could be made
     * @param timelapseNote  why there is no video, otherwise null
     */
    public record Night(String cameraId, String siteName, LocalDate date, String timeZone, Instant start, Instant end,
                        List<FrameRef> frames, String keogram, int keogramWidth, int keogramHeight,
                        String timelapse, String timelapseNote, Instant builtAt) {
    }

    private final ImageRepository images;
    private final CameraRepository cameras;
    private final ImagePaths imagePaths;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Path dir;
    private final double sunAltitudeDeg;
    private final int minFrames;
    private final int keogramWidth;
    private final int keogramHeight;
    private final String ffmpeg;
    private final int fps;
    private final int videoWidth;
    private final Duration ffmpegTimeout;

    public NightlyService(
            ImageRepository images,
            CameraRepository cameras,
            ImagePaths imagePaths,
            ObjectMapper mapper,
            @Value("${app.nightly.enabled:true}") boolean enabled,
            @Value("${app.nightly.dir:./data/nightly}") String dir,
            @Value("${app.nightly.sun-altitude-deg:-6}") double sunAltitudeDeg,
            @Value("${app.nightly.min-frames:12}") int minFrames,
            @Value("${app.nightly.keogram-width:960}") int keogramWidth,
            @Value("${app.nightly.keogram-height:540}") int keogramHeight,
            @Value("${app.nightly.ffmpeg-command:ffmpeg}") String ffmpeg,
            @Value("${app.nightly.fps:12}") int fps,
            @Value("${app.nightly.video-width:1080}") int videoWidth,
            @Value("${app.nightly.ffmpeg-timeout:PT10M}") Duration ffmpegTimeout) {
        this.images = images;
        this.cameras = cameras;
        this.imagePaths = imagePaths;
        this.mapper = mapper;
        this.enabled = enabled;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        this.sunAltitudeDeg = sunAltitudeDeg;
        this.minFrames = Math.max(2, minFrames);
        this.keogramWidth = Math.max(16, keogramWidth);
        this.keogramHeight = Math.max(16, keogramHeight);
        this.ffmpeg = ffmpeg;
        this.fps = Math.max(1, fps);
        this.videoWidth = Math.max(160, videoWidth) / 2 * 2;
        this.ffmpegTimeout = ffmpegTimeout;
    }

    // ---- schedule ---------------------------------------------------------------------------

    @Scheduled(cron = "${app.nightly.cron:0 20 * * * *}")
    public void buildLatestNights() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        for (Camera camera : cameras.findAll()) {
            try {
                LocalDate yesterday = LocalDate.ofInstant(now, zone(camera)).minusDays(1);
                Optional<SunPosition.Night> night = nightOf(camera, yesterday);
                if (night.isPresent() && now.isAfter(night.get().end().plus(Duration.ofMinutes(15)))
                        && !Files.exists(nightDir(camera.getCameraId(), yesterday).resolve(SUMMARY))) {
                    build(camera, yesterday);
                }
            } catch (Exception e) {   // one camera must not stop the others
                log.warn("Nightly summary of {} failed: {}", camera.getCameraId(), e.toString());
            }
        }
    }

    // ---- reading ----------------------------------------------------------------------------

    /** Built nights, newest first; of one camera when {@code cameraId} is given. */
    public List<Night> list(String cameraId, int limit) {
        if (cameraId != null && !CAMERA_ID.matcher(cameraId).matches()) {
            return List.of();
        }
        List<Path> summaries = new ArrayList<>();
        try (Stream<Path> cameraDirs = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            for (Path cameraDir : (Iterable<Path>) cameraDirs::iterator) {
                if (cameraId != null && !cameraDir.getFileName().toString().equals(cameraId)) {
                    continue;
                }
                try (Stream<Path> nights = Files.isDirectory(cameraDir) ? Files.list(cameraDir) : Stream.empty()) {
                    nights.map(night -> night.resolve(SUMMARY)).filter(Files::isRegularFile).forEach(summaries::add);
                }
            }
        } catch (IOException e) {
            log.warn("Cannot list nightly summaries: {}", e.toString());
        }
        // <camera>/<date>/night.json: ISO dates sort as text
        summaries.sort(Comparator.comparing((Path p) -> p.getParent().getFileName().toString()).reversed()
                .thenComparing(p -> p.getParent().getParent().getFileName().toString()));
        List<Night> result = new ArrayList<>();
        for (Path summary : summaries) {
            if (result.size() >= Math.max(1, Math.min(limit, 366))) {
                break;
            }
            try {
                result.add(mapper.readValue(summary.toFile(), Night.class));
            } catch (IOException e) {
                log.warn("Unreadable nightly summary {}: {}", summary, e.toString());
            }
        }
        return result;
    }

    /** The keogram or video of one night, when it exists. */
    public Optional<Path> file(String cameraId, String date, String name) {
        if (!CAMERA_ID.matcher(cameraId).matches() || !(KEOGRAM.equals(name) || TIMELAPSE.equals(name))) {
            return Optional.empty();
        }
        try {
            Path path = nightDir(cameraId, LocalDate.parse(date)).resolve(name);
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    // ---- building ---------------------------------------------------------------------------

    /** Builds (or rebuilds) one night of one camera. Empty when the camera is unknown or took too few frames. */
    public Optional<Night> build(String cameraId, LocalDate date) throws IOException {
        Optional<Camera> camera = cameras.findAll().stream().filter(c -> cameraId.equals(c.getCameraId())).findFirst();
        return camera.isPresent() ? build(camera.get(), date) : Optional.empty();
    }

    private synchronized Optional<Night> build(Camera camera, LocalDate date) throws IOException {
        String cameraId = camera.getCameraId();
        if (cameraId == null || !CAMERA_ID.matcher(cameraId).matches()) {
            return Optional.empty();
        }
        Optional<SunPosition.Night> span = nightOf(camera, date);
        if (span.isEmpty()) {
            return Optional.empty();
        }
        Instant start = span.get().start();
        Instant end = span.get().end();
        List<Image> rows = images.findByCameraBetween(cameraId,
                LocalDateTime.ofInstant(start, ZoneOffset.UTC), LocalDateTime.ofInstant(end, ZoneOffset.UTC));
        List<FrameRef> frames = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (Image row : rows) {
            Optional<Path> jpeg = imagePaths.jpegOf(row.getImgPath());
            if (jpeg.isPresent()) {
                frames.add(new FrameRef(row.getImgId(), row.getTimestamp()));
                files.add(jpeg.get());
            }
        }
        if (frames.size() < minFrames) {
            log.info("Nightly summary of {} {}: {} frame(s), fewer than {}; nothing built", cameraId, date, frames.size(), minFrames);
            return Optional.empty();
        }

        Path target = nightDir(cameraId, date);
        Files.createDirectories(target);
        List<Instant> times = frames.stream().map(FrameRef::at).toList();
        BufferedImage keogram = keogram(files, times, start, end, keogramWidth, keogramHeight);
        Path keogramTmp = target.resolve(KEOGRAM + ".tmp");
        if (!ImageIO.write(keogram, "jpg", keogramTmp.toFile())) {
            throw new IOException("No JPEG writer available");
        }
        Files.move(keogramTmp, target.resolve(KEOGRAM), StandardCopyOption.REPLACE_EXISTING);

        String note = timelapse(files, target);
        if (note != null) {
            Files.deleteIfExists(target.resolve(TIMELAPSE));   // a rebuild must not leave an older video behind
        }
        Night night = new Night(cameraId, camera.getSiteName(), date, zone(camera).getId(), start, end, frames,
                KEOGRAM, keogram.getWidth(), keogram.getHeight(), note == null ? TIMELAPSE : null, note, Instant.now());
        Path summaryTmp = target.resolve(SUMMARY + ".tmp");
        mapper.writeValue(summaryTmp.toFile(), night);
        Files.move(summaryTmp, target.resolve(SUMMARY), StandardCopyOption.REPLACE_EXISTING);
        log.info("Nightly summary of {} {}: {} frames, keogram{}", cameraId, date, frames.size(),
                note == null ? " and time-lapse" : "; no time-lapse (" + note + ")");
        return Optional.of(night);
    }

    /** The night whose evening fell on {@code date} at the camera. */
    Optional<SunPosition.Night> nightOf(Camera camera, LocalDate date) {
        ZoneId zone = zone(camera);
        Double latitude = camera.getLatitude();
        Double longitude = camera.getLongitude();
        if (latitude == null || longitude == null) {
            Optional<ObservingSite> site = ObservingSite.find(camera.getCameraId());
            latitude = site.map(ObservingSite::latitudeDeg).orElse(null);
            longitude = site.map(ObservingSite::longitudeDeg).orElse(null);
        }
        if (latitude == null || longitude == null) {
            // No coordinates on record: 18:00 to 06:00 local time
            return Optional.of(new SunPosition.Night(date.atTime(18, 0).atZone(zone).toInstant(),
                    date.plusDays(1).atTime(6, 0).atZone(zone).toInstant()));
        }
        Instant nextNoon = date.plusDays(1).atTime(LocalTime.NOON).atZone(zone).toInstant();
        return SunPosition.lastCompletedNight(latitude, longitude, sunAltitudeDeg, nextNoon);
    }

    private static ZoneId zone(Camera camera) {
        try {
            return camera.getTimeZone() == null || camera.getTimeZone().isBlank()
                    ? ZoneOffset.UTC : ZoneId.of(camera.getTimeZone().trim());
        } catch (java.time.DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private Path nightDir(String cameraId, LocalDate date) {
        return dir.resolve(cameraId).resolve(date.toString());
    }

    /**
     * Keogram of frames taken at {@code times} (ascending) between {@code start} and {@code end}.
     * Each frame's centre column fills the picture from its own time to the next frame's; a wait
     * of more than three typical intervals is cut short, which leaves the rest of the gap black.
     */
    static BufferedImage keogram(List<Path> files, List<Instant> times, Instant start, Instant end, int width, int height)
            throws IOException {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double span = Math.max(1, Duration.between(start, end).toMillis());
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            intervals.add(Duration.between(times.get(i - 1), times.get(i)).toMillis());
        }
        intervals.sort(null);
        long typical = intervals.isEmpty() ? (long) span : Math.max(1, intervals.get(intervals.size() / 2));
        for (int i = 0; i < files.size(); i++) {
            long from = Duration.between(start, times.get(i)).toMillis();
            long until = i + 1 < times.size() ? Duration.between(start, times.get(i + 1)).toMillis() : from + typical;
            until = Math.min(until, from + 3 * typical);
            int x0 = (int) Math.floor(from / span * width);
            int x1 = Math.max(x0 + 1, (int) Math.ceil(until / span * width));
            if (x1 <= 0 || x0 >= width) {
                continue;
            }
            BufferedImage column;
            try {
                column = centreColumn(files.get(i));
            } catch (IOException | RuntimeException e) {
                continue;   // a truncated upload leaves its slot black
            }
            g.drawImage(column, x0, 0, x1 - x0, height, null);
        }
        g.dispose();
        return out;
    }

    /** The middle three pixel columns of an image, read without keeping the rest. */
    private static BufferedImage centreColumn(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new IOException("Cannot open " + file);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("Not an image: " + file);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(Math.max(0, w / 2 - 1), 0, Math.min(3, w), reader.getHeight(0)));
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Encodes the frames into {@code timelapse.mp4}. Returns null on success, otherwise the reason there is no video. */
    private String timelapse(List<Path> files, Path target) {
        Path list = target.resolve("frames.txt");
        Path tmp = target.resolve("timelapse.tmp.mp4");
        Path logFile = target.resolve("ffmpeg.log");
        try {
            StringBuilder concat = new StringBuilder("ffconcat version 1.0\n");
            for (Path file : files) {
                concat.append("file '").append(file.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n")
                        .append("duration ").append(String.format(Locale.ROOT, "%.5f", 1.0 / fps)).append('\n');
            }
            Files.writeString(list, concat, StandardCharsets.UTF_8);
            Process process;
            try {
                process = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                        "-f", "concat", "-safe", "0", "-i", list.toString(),
                        "-vf", "scale=" + videoWidth + ":-2,format=yuv420p", "-r", String.valueOf(fps),
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-movflags", "+faststart", "-an", tmp.toString())
                        .redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            } catch (IOException e) {
                return "ffmpeg is not installed on the server";
            }
            if (!process.waitFor(ffmpegTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "ffmpeg did not finish within " + ffmpegTimeout.toMinutes() + " min";
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(tmp) || Files.size(tmp) == 0) {
                String output = Files.isRegularFile(logFile) ? Files.readString(logFile).strip() : "";
                return "ffmpeg failed" + (output.isEmpty() ? "" : ": " + output.substring(Math.max(0, output.length() - 300)));
            }
            Files.move(tmp, target.resolve(TIMELAPSE), StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException e) {
            return "time-lapse could not be written: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            for (Path leftover : List.of(list, tmp, logFile)) {
                try {
                    Files.deleteIfExists(leftover);
                } catch (IOException ignored) {
                    // harmless
                }
            }
        }
    }
}
