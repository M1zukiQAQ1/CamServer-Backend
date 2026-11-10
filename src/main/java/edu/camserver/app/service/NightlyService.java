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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One summary per camera per night: a keogram and a time-lapse video.
 *
 * <p>A night runs while the Sun is more than {@code app.nightly.sun-altitude-deg} below the
 * horizon at the camera and is named after the local date of its evening. The keogram puts the
 * centre column of every frame side by side, with time running left to right in proportion, so a
 * gap in the uploads stays a black gap. The video plays the frames at {@code app.nightly.fps}; it
 * needs ffmpeg on the server and is simply left out when ffmpeg is missing or fails. Every video
 * frame carries a label in its bottom left corner: the site name, the UTC time the frame was taken
 * and, when they are on record, the camera's coordinates.
 *
 * <p>Results live in {@code app.nightly.dir/<cameraId>/<date>/}: {@code night.json},
 * {@code keogram.jpg}, {@code timelapse.mp4}. At start-up and every hour, each completed night of
 * the last {@code app.nightly.backfill-days} days that has no summary yet is built, newest first,
 * on a thread of its own so a long catch-up does not hold up the other scheduled jobs. A night
 * whose video has no label (built by an older version, or before ffmpeg was installed) is built
 * again, once per run of the server; so is any older night on disk in that state.
 *
 * <p>Anyone can also ask for the video of one completed night ({@link #request}); it is built
 * on a queue of its own, one night at a time, and {@link #status} tells how far it is.
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
     * @param timelapseLabelled  whether the video frames carry the site, UTC time and coordinates
     */
    public record Night(String cameraId, String siteName, LocalDate date, String timeZone, Instant start, Instant end,
                        List<FrameRef> frames, String keogram, int keogramWidth, int keogramHeight,
                        String timelapse, String timelapseNote, boolean timelapseLabelled, Instant builtAt) {
    }

    /**
     * Where the video of one night stands.
     *
     * @param state   {@code ready}, {@code queued}, {@code building}, {@code none} (nobody asked yet)
     *                or {@code unavailable} (it cannot be made; {@code message} says why)
     * @param night   the night's summary once there is one
     */
    public record VideoStatus(String state, String message, Night night) {
    }

    private static final int MAX_WAITING = 24;
    private static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    private record Failure(String message, Instant at) {
    }

    /** Requested nights that are waiting ("queued") or being built ("building"). */
    private final java.util.Map<String, String> requested = new ConcurrentHashMap<>();
    private final java.util.Map<String, Failure> failures = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService onDemand = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "nightly-on-demand");
        thread.setDaemon(true);
        return thread;
    });

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
    private final int backfillDays;
    private final AtomicBoolean catchingUp = new AtomicBoolean();
    /** Past nights found to have too few frames; they cannot gain any, so they are not looked at again. */
    private final Set<String> nothingToBuild = ConcurrentHashMap.newKeySet();
    /** Nights whose summary is up to date, or was already rebuilt by this process; not looked at again. */
    private final Set<String> settled = ConcurrentHashMap.newKeySet();

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
            @Value("${app.nightly.ffmpeg-timeout:PT10M}") Duration ffmpegTimeout,
            @Value("${app.nightly.backfill-days:60}") int backfillDays) {
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
        this.backfillDays = Math.max(1, Math.min(backfillDays, 366));
    }

    // ---- schedule ---------------------------------------------------------------------------

    @Scheduled(cron = "${app.nightly.cron:0 20 * * * *}")
    @EventListener(ApplicationReadyEvent.class)
    public void buildMissingNights() {
        if (!enabled || !catchingUp.compareAndSet(false, true)) {
            return;   // switched off, or the previous catch-up is still running
        }
        Thread worker = new Thread(() -> {
            try {
                catchUp(Instant.now());
            } finally {
                catchingUp.set(false);
            }
        }, "nightly-catch-up");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Builds every completed night of the last {@code backfill-days} days that has no summary,
     * newest first. Returns how many were built.
     */
    int catchUp(Instant now) {
        List<Camera> all = cameras.findAll();
        int built = 0;
        for (int back = 0; back <= backfillDays; back++) {
            for (Camera camera : all) {
                String cameraId = camera.getCameraId();
                if (cameraId == null || !CAMERA_ID.matcher(cameraId).matches()) {
                    continue;
                }
                try {
                    LocalDate date = LocalDate.ofInstant(now, zone(camera)).minusDays(back);
                    String key = cameraId + "/" + date;
                    Optional<SunPosition.Night> night = nightOf(camera, date);
                    if (night.isEmpty() || !now.isAfter(night.get().end().plus(Duration.ofMinutes(15)))
                            || nothingToBuild.contains(key) || settled.contains(key)) {
                        continue;
                    }
                    Path summary = nightDir(cameraId, date).resolve(SUMMARY);
                    boolean upToDate = Files.exists(summary) && hasLabelledTimelapse(summary);
                    settled.add(key);   // whatever comes of the build, one try per run of the server
                    if (upToDate) {
                        continue;
                    }
                    if (build(camera, date).isPresent()) {
                        built++;
                    } else {
                        nothingToBuild.add(key);
                    }
                } catch (Exception e) {   // one night must not stop the others
                    log.warn("Nightly summary of {} failed: {}", cameraId, e.toString());
                }
            }
        }
        built += relabelOlderNights(all);
        if (built > 0) {
            log.info("Nightly catch-up built {} night(s)", built);
        }
        return built;
    }

    /** Nights on disk from before the catch-up window whose video has no label: built again, newest first. */
    private int relabelOlderNights(List<Camera> all) {
        int built = 0;
        for (Camera camera : all) {
            String cameraId = camera.getCameraId();
            if (cameraId == null || !CAMERA_ID.matcher(cameraId).matches() || !Files.isDirectory(dir.resolve(cameraId))) {
                continue;
            }
            List<Path> nights;
            try (Stream<Path> listing = Files.list(dir.resolve(cameraId))) {
                nights = listing.sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
            } catch (IOException e) {
                continue;
            }
            for (Path night : nights) {
                String key = cameraId + "/" + night.getFileName();
                Path summary = night.resolve(SUMMARY);
                if (settled.contains(key) || !Files.isRegularFile(summary)) {
                    continue;
                }
                settled.add(key);
                try {
                    if (!hasLabelledTimelapse(summary) && build(camera, LocalDate.parse(night.getFileName().toString())).isPresent()) {
                        built++;
                    }
                } catch (Exception e) {
                    log.warn("Nightly summary of {} failed: {}", key, e.toString());
                }
            }
        }
        return built;
    }

    // ---- on demand --------------------------------------------------------------------------

    /** Where the video of one night stands, without starting anything. */
    public VideoStatus status(String cameraId, LocalDate date) {
        if (cameraId == null || !CAMERA_ID.matcher(cameraId).matches()) {
            return new VideoStatus("unavailable", "Unknown camera.", null);
        }
        String key = cameraId + "/" + date;
        String waiting = requested.get(key);
        Night night = read(nightDir(cameraId, date).resolve(SUMMARY));
        if (waiting != null) {
            return new VideoStatus(waiting, null, night);
        }
        if (night != null && night.timelapse() != null && (night.timelapseLabelled() || settled.contains(key))) {
            return new VideoStatus("ready", null, night);
        }
        Failure failure = failures.get(key);
        if (failure != null) {
            return new VideoStatus("unavailable", failure.message(), night);
        }
        return new VideoStatus("none", null, night);
    }

    /**
     * Asks for the video of one completed night. Returns at once: {@code ready} when it is there
     * already, otherwise {@code queued}/{@code building}, or {@code unavailable} with the reason.
     * A night that failed is not tried again for ten minutes.
     */
    public VideoStatus request(String cameraId, LocalDate date, Instant now) {
        VideoStatus current = status(cameraId, date);
        String key = cameraId + "/" + date;
        Failure failure = failures.get(key);
        boolean retry = failure != null && now.isAfter(failure.at().plus(RETRY_AFTER));
        if (!current.state().equals("none") && !(retry && current.state().equals("unavailable") && current.message() != null
                && current.message().equals(failure.message()))) {
            return current;
        }
        Optional<Camera> camera = cameras.findAll().stream().filter(c -> cameraId.equals(c.getCameraId())).findFirst();
        if (camera.isEmpty()) {
            return new VideoStatus("unavailable", "Unknown camera.", null);
        }
        if (!enabled) {
            return new VideoStatus("unavailable", "Nightly videos are switched off on this server.", current.night());
        }
        Optional<SunPosition.Night> span = nightOf(camera.get(), date);
        if (span.isEmpty() || !now.isAfter(span.get().end())) {
            return new VideoStatus("unavailable", "That night is not over yet.", current.night());
        }
        if (requested.size() >= MAX_WAITING) {
            return new VideoStatus("unavailable", "Too many videos are waiting to be made; try again in a few minutes.", current.night());
        }
        if (requested.putIfAbsent(key, "queued") == null) {
            failures.remove(key);
            onDemand.execute(() -> buildRequested(camera.get(), date, key));
        }
        return new VideoStatus(requested.getOrDefault(key, "queued"), null, current.night());
    }

    private void buildRequested(Camera camera, LocalDate date, String key) {
        try {
            requested.replace(key, "building");
            Optional<Night> night = build(camera, date);
            settled.add(key);
            if (night.isEmpty()) {
                failures.put(key, new Failure("The camera took fewer than " + minFrames + " frames that night.", Instant.now()));
            } else if (night.get().timelapse() == null) {
                failures.put(key, new Failure(night.get().timelapseNote(), Instant.now()));
            }
        } catch (Exception e) {
            log.warn("Requested video of {} failed: {}", key, e.toString());
            failures.put(key, new Failure("The video could not be made.", Instant.now()));
        } finally {
            requested.remove(key);
        }
    }

    private Night read(Path summary) {
        try {
            return Files.isRegularFile(summary) ? mapper.readValue(summary.toFile(), Night.class) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean hasLabelledTimelapse(Path summary) {
        try {
            return mapper.readTree(summary.toFile()).path("timelapseLabelled").asBoolean(false);
        } catch (IOException e) {
            return false;
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

        Video video = timelapse(files, times, labelLines(camera), target);
        String note = video.note();
        if (note != null) {
            Files.deleteIfExists(target.resolve(TIMELAPSE));   // a rebuild must not leave an older video behind
        }
        Night night = new Night(cameraId, camera.getSiteName(), date, zone(camera).getId(), start, end, frames,
                KEOGRAM, keogram.getWidth(), keogram.getHeight(), note == null ? TIMELAPSE : null, note, video.labelled(), Instant.now());
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
        double[] latLon = coordinates(camera);
        if (latLon == null) {
            // No coordinates on record: 18:00 to 06:00 local time
            return Optional.of(new SunPosition.Night(date.atTime(18, 0).atZone(zone).toInstant(),
                    date.plusDays(1).atTime(6, 0).atZone(zone).toInstant()));
        }
        Instant nextNoon = date.plusDays(1).atTime(LocalTime.NOON).atZone(zone).toInstant();
        return SunPosition.lastCompletedNight(latLon[0], latLon[1], sunAltitudeDeg, nextNoon);
    }

    /** Latitude and longitude in degrees, from the database or else the site table; null when neither has them. */
    private static double[] coordinates(Camera camera) {
        Double latitude = camera.getLatitude();
        Double longitude = camera.getLongitude();
        if (latitude == null || longitude == null) {
            Optional<ObservingSite> site = ObservingSite.find(camera.getCameraId());
            latitude = site.map(ObservingSite::latitudeDeg).orElse(null);
            longitude = site.map(ObservingSite::longitudeDeg).orElse(null);
        }
        return latitude == null || longitude == null ? null : new double[] {latitude, longitude};
    }

    /**
     * The label lines of a camera's video: the site name (the camera id when it has none), a
     * {@code null} that stands for the frame's UTC time, and the coordinates when they are on record.
     */
    static List<String> labelLines(Camera camera) {
        String name = camera.getSiteName() == null || camera.getSiteName().isBlank()
                ? camera.getCameraId() : camera.getSiteName().trim();
        List<String> lines = new ArrayList<>();
        lines.add(name.length() > 40 ? name.substring(0, 40) : name);
        lines.add(null);
        double[] latLon = coordinates(camera);
        if (latLon != null) {
            lines.add(String.format(Locale.ROOT, "%.4f\u00b0 %s  %.4f\u00b0 %s", Math.abs(latLon[0]), latLon[0] < 0 ? "S" : "N",
                    Math.abs(latLon[1]), latLon[1] < 0 ? "W" : "E"));
        }
        return lines;
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

    private static final DateTimeFormatter LABEL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * One transparent picture per frame, all of the same size, with the label lines in white on a
     * dark outline; the {@code null} line becomes the frame's UTC time. Written as
     * {@code 000000.png}, {@code 000001.png}, ... into {@code into}.
     */
    static List<Path> writeLabels(List<String> lines, List<Instant> times, int videoWidth, Path into) throws IOException {
        Font font = new Font(Font.MONOSPACED, Font.BOLD, Math.max(11, Math.round(videoWidth / 48f)));
        FontRenderContext context = new FontRenderContext(null, true, true);
        float lineHeight = font.getLineMetrics("Xg", context).getHeight();
        float ascent = font.getLineMetrics("Xg", context).getAscent();
        int pad = Math.max(2, font.getSize() / 6);
        double widest = 0;
        for (String line : lines) {
            String text = line == null ? LABEL_TIME.format(Instant.EPOCH) : line;   // every time is as wide as this one
            widest = Math.max(widest, font.getStringBounds(text, context).getWidth());
        }
        int width = (int) Math.ceil(widest) + 2 * pad;
        int height = (int) Math.ceil(lineHeight * lines.size()) + 2 * pad;
        Files.createDirectories(into);
        List<Path> written = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = picture.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(Math.max(2f, font.getSize() / 6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int row = 0; row < lines.size(); row++) {
                String text = lines.get(row) == null ? LABEL_TIME.format(times.get(i)) : lines.get(row);
                if (text.isBlank()) {
                    continue;
                }
                Shape glyphs = new TextLayout(text, font, context)
                        .getOutline(AffineTransform.getTranslateInstance(pad, pad + ascent + row * lineHeight));
                g.setColor(new Color(0, 0, 0, 200));
                g.draw(glyphs);
                g.setColor(Color.WHITE);
                g.fill(glyphs);
            }
            g.dispose();
            Path file = into.resolve(String.format(Locale.ROOT, "%06d.png", i));
            if (!ImageIO.write(picture, "png", file.toFile())) {
                throw new IOException("No PNG writer available");
            }
            written.add(file);
        }
        return written;
    }

    /** @param note null when the video was made, otherwise why not; @param labelled whether its frames carry the label */
    private record Video(String note, boolean labelled) {
    }

    private String concatList(List<Path> files) {
        StringBuilder concat = new StringBuilder("ffconcat version 1.0\n");
        for (Path file : files) {
            concat.append("file '").append(file.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n")
                    .append("duration ").append(String.format(Locale.ROOT, "%.5f", 1.0 / fps)).append('\n');
        }
        return concat.toString();
    }

    /**
     * Encodes the frames into {@code timelapse.mp4}, each with its label in the bottom left corner.
     * When the labels cannot be drawn (a server without fonts), the video is made without them.
     */
    private Video timelapse(List<Path> files, List<Instant> times, List<String> labelLines, Path target) {
        Path list = target.resolve("frames.txt");
        Path labelList = target.resolve("labels.txt");
        Path labelDir = target.resolve("labels");
        Path tmp = target.resolve("timelapse.tmp.mp4");
        Path logFile = target.resolve("ffmpeg.log");
        try {
            Files.writeString(list, concatList(files), StandardCharsets.UTF_8);
            boolean labelled = false;
            try {
                Files.writeString(labelList, concatList(writeLabels(labelLines, times, videoWidth, labelDir)), StandardCharsets.UTF_8);
                labelled = true;
            } catch (IOException | RuntimeException | LinkageError | InternalError e) {
                log.warn("Time-lapse labels could not be drawn, the video goes without: {}", e.toString());
            }
            List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "concat", "-safe", "0", "-i", list.toString()));
            if (labelled) {
                int margin = Math.max(4, videoWidth / 90);
                command.addAll(List.of("-f", "concat", "-safe", "0", "-i", labelList.toString(),
                        "-filter_complex", "[0:v]scale=" + videoWidth + ":-2[sky];[sky][1:v]overlay=x=" + margin
                                + ":y=main_h-overlay_h-" + margin + ":eof_action=repeat,format=yuv420p"));
            } else {
                command.addAll(List.of("-vf", "scale=" + videoWidth + ":-2,format=yuv420p"));
            }
            command.addAll(List.of("-r", String.valueOf(fps),
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-movflags", "+faststart", "-an", tmp.toString()));
            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            } catch (IOException e) {
                return new Video("ffmpeg is not installed on the server", false);
            }
            if (!process.waitFor(ffmpegTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Video("ffmpeg did not finish within " + ffmpegTimeout.toMinutes() + " min", false);
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(tmp) || Files.size(tmp) == 0) {
                String output = Files.isRegularFile(logFile) ? Files.readString(logFile).strip() : "";
                return new Video("ffmpeg failed" + (output.isEmpty() ? "" : ": " + output.substring(Math.max(0, output.length() - 300))), false);
            }
            Files.move(tmp, target.resolve(TIMELAPSE), StandardCopyOption.REPLACE_EXISTING);
            return new Video(null, labelled);
        } catch (IOException e) {
            return new Video("time-lapse could not be written: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Video("interrupted", false);
        } finally {
            try (Stream<Path> labels = Files.isDirectory(labelDir) ? Files.list(labelDir) : Stream.empty()) {
                for (Path label : (Iterable<Path>) labels::iterator) {
                    Files.deleteIfExists(label);
                }
            } catch (IOException ignored) {
                // harmless
            }
            for (Path leftover : List.of(list, labelList, labelDir, tmp, logFile)) {
                try {
                    Files.deleteIfExists(leftover);
                } catch (IOException ignored) {
                    // harmless
                }
            }
        }
    }
}
