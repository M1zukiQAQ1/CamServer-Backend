package edu.camserver.app.service;

import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.Image;
import edu.camserver.app.model.platesolve.PlateSolveCrop;
import edu.camserver.app.model.platesolve.PlateSolveProgress;
import edu.camserver.app.model.platesolve.PlateSolveResult;
import edu.camserver.app.model.platesolve.PlateSolveSolution;
import edu.camserver.app.model.platesolve.PlateSolveStar;
import edu.camserver.app.model.platesolve.PlateSolveStarIdentifier;
import edu.camserver.app.model.platesolve.PlateSolveStarLink;
import edu.camserver.app.model.platesolve.PlateSolveStatus;
import edu.camserver.app.model.archive.StoredImage;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PlateSolveService {
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "\\b(HIP|HD|HR|SAO|TYC)\\s*([A-Za-z0-9+._\\-]+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GAIA_IDENTIFIER_PATTERN = Pattern.compile(
            "\\bGAIA(?:\\s+DR\\d+)?\\s*([0-9]+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final int FITS_BLOCK_SIZE = 2880;
    private static final List<String> FITS_EXTENSIONS = List.of(".fits", ".fit", ".fts");
    private static final List<String> RASTER_EXTENSIONS = List.of(".jpg", ".jpeg", ".png");
    private static final URI DEFAULT_ONLINE_CATALOG_URL = URI.create("https://simbad.cds.unistra.fr/simbad/sim-tap/sync");
    private static final int QHY5III678_EFFECTIVE_WIDTH_PX = 3856;
    private static final int QHY5III678_EFFECTIVE_HEIGHT_PX = 2180;
    private static final double QHY5III678_PIXEL_SIZE_UM = 2.0;
    private static final double[] FISHEYE_RADIAL_POWERS = {
            0.64, 0.68, 0.72, 0.76, 0.80, 0.84, 0.88, 0.92, 0.96, 1.00, 1.06, 1.12, 1.18, 1.25, 1.32, 1.40
    };
    private static final double[] DEFAULT_RADIAL_POWERS = {0.92, 1.00, 1.08};
    private static final int UNDISTORTED_SOLVE_SIZE_PX = 1600;
    private static final double UNDISTORTED_SOLVE_FIELD_WIDTH_DEG = 100.0;
    private static final double UNDISTORTED_SOLVE_SEARCH_RADIUS_DEG = 70.0;
    private static final int UNDISTORTED_SOLVE_TWEAK_ORDER = 2;
    private static final int UNDISTORTED_XYLIST_MAX_STARS = 240;
    private static final double UNDISTORTED_XYLIST_MIN_ALTITUDE_DEG = 32.0;
    private static final double UNDISTORTED_XYLIST_MARGIN_PX = 48.0;
    private static final double UNDISTORTED_XYLIST_MIN_DISTANCE_PX = 12.0;
    private static final int UNDISTORTED_XYLIST_GRID = 16;
    private static final int UNDISTORTED_XYLIST_MAX_PER_CELL = 2;
    private static final double CONFIRMED_STAR_MAX_ERROR_DEG = 1.0 / 60.0;
    // Gaussian-windowed (SExtractor XWIN-style) centroid on linear pixels; values match the
    // lens calibration in scripts/calibration, which measured its stars with the same algorithm.
    private static final double LINEAR_CENTROID_SIGMA_PX = 1.4;
    private static final int LINEAR_CENTROID_HALF_WINDOW_PX = 7;
    private static final int LINEAR_BACKGROUND_INNER_PX = 8;
    private static final int LINEAR_BACKGROUND_OUTER_PX = 14;
    private static final double LINEAR_CENTROID_MAX_SHIFT_PX = 1.5;
    // J2000.0 = JD 2451545.0 TT = 2000-01-01T11:58:55.816Z.
    private static final long J2000_EPOCH_MILLIS = 946_727_935_816L;
    private static final double MILLIS_PER_JULIAN_YEAR = 365.25 * 86_400_000.0;
    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    private final ImageService imageService;
    private final ImagePaths imagePaths;
    private final ImageArchiveService archiveService;
    private final PlateSolveMaskService maskService;
    private final LostPlateSolver lostSolver;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Map<Long, PlateSolveResult> resultCache = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<PlateSolveResult>> runningJobs = new ConcurrentHashMap<>();
    private final Map<Long, PlateSolveProgress> progressCache = new ConcurrentHashMap<>();
    private final Path workDir;
    private final boolean enabled;
    private final String fitsImcopyCommand;
    private final String resolvedFitsImcopyCommand;
    private final int timeoutSeconds;
    private final int maxStars;
    private final int maxStarArea;
    private final int maxStarDiameter;
    private final int maxStarBackground;
    private final int cropThreshold;
    private final int fitsCropThreshold;
    private final int starMinContrast;
    private final int fitsStarMinContrast;
    private final double starContrastPercentile;
    private final double fitsStarContrastPercentile;
    private final double siteLatitudeDeg;
    private final double siteLongitudeDeg;
    private final String catalogPath;
    private final double catalogMatchRadiusDeg;
    private final boolean onlineCatalogEnabled;
    private final URI onlineCatalogUrl;
    private final Path onlineCatalogCacheFile;
    private final Duration onlineCatalogCacheTtl;
    private final double onlineCatalogMagnitudeLimit;
    private final int onlineCatalogMaxRows;
    private final Duration onlineCatalogTimeout;
    private volatile List<CatalogStar> catalogStars;
    private volatile List<CatalogStar> onlineCatalogStars;
    private volatile EpochCatalog epochCatalog;

    public PlateSolveService(
            ImageService imageService,
            ImagePaths imagePaths,
            ImageArchiveService archiveService,
            PlateSolveMaskService maskService,
            LostPlateSolver lostSolver,
            @Value("${app.plate-solve.enabled:true}") boolean enabled,
            @Value("${app.plate-solve.fits-imcopy-command:imcopy}") String fitsImcopyCommand,
            @Value("${app.plate-solve.work-dir:}") String configuredWorkDir,
            @Value("${app.plate-solve.fits-extraction-timeout-seconds:30}") int timeoutSeconds,
            @Value("${app.plate-solve.max-stars:800}") int maxStars,
            @Value("${app.plate-solve.max-star-area:80}") int maxStarArea,
            @Value("${app.plate-solve.max-star-diameter:18}") int maxStarDiameter,
            @Value("${app.plate-solve.max-star-background:95}") int maxStarBackground,
            @Value("${app.plate-solve.crop-threshold:12}") int cropThreshold,
            @Value("${app.plate-solve.fits-crop-threshold:32}") int fitsCropThreshold,
            @Value("${app.plate-solve.star-min-contrast:18}") int starMinContrast,
            @Value("${app.plate-solve.fits-star-min-contrast:42}") int fitsStarMinContrast,
            @Value("${app.plate-solve.star-contrast-percentile:0.9985}") double starContrastPercentile,
            @Value("${app.plate-solve.fits-star-contrast-percentile:0.9994}") double fitsStarContrastPercentile,
            @Value("${app.plate-solve.site-latitude-deg:34.41403}") double siteLatitudeDeg,
            @Value("${app.plate-solve.site-longitude-deg:-119.84300}") double siteLongitudeDeg,
            @Value("${app.plate-solve.catalog-path:}") String catalogPath,
            @Value("${app.plate-solve.catalog-match-radius-deg:0.05}") double catalogMatchRadiusDeg,
            @Value("${app.plate-solve.online-catalog.enabled:true}") boolean onlineCatalogEnabled,
            @Value("${app.plate-solve.online-catalog.url:https://simbad.cds.unistra.fr/simbad/sim-tap/sync}") String onlineCatalogUrl,
            @Value("${app.plate-solve.online-catalog.cache-file:}") String onlineCatalogCacheFile,
            @Value("${app.plate-solve.online-catalog.cache-ttl-hours:720}") long onlineCatalogCacheTtlHours,
            @Value("${app.plate-solve.online-catalog.magnitude-limit:8.0}") double onlineCatalogMagnitudeLimit,
            @Value("${app.plate-solve.online-catalog.max-rows:30000}") int onlineCatalogMaxRows,
            @Value("${app.plate-solve.online-catalog.timeout-ms:10000}") long onlineCatalogTimeoutMs,
            @Value("${app.plate-solve.worker-threads:2}") int workerThreads) {
        this.imageService = imageService;
        this.imagePaths = imagePaths;
        this.archiveService = archiveService;
        this.maskService = maskService;
        this.lostSolver = lostSolver;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, onlineCatalogTimeoutMs)))
                .build();
        this.enabled = enabled;
        this.fitsImcopyCommand = fitsImcopyCommand == null || fitsImcopyCommand.isBlank() ? "imcopy" : fitsImcopyCommand;
        this.resolvedFitsImcopyCommand = resolveSolverCommand(this.fitsImcopyCommand);
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        this.maxStars = Math.max(50, maxStars);
        this.maxStarArea = Math.max(4, maxStarArea);
        this.maxStarDiameter = Math.max(4, maxStarDiameter);
        this.maxStarBackground = Math.max(0, Math.min(255, maxStarBackground));
        this.cropThreshold = Math.max(0, Math.min(255, cropThreshold));
        this.fitsCropThreshold = Math.max(0, Math.min(255, fitsCropThreshold));
        this.starMinContrast = Math.max(1, Math.min(255, starMinContrast));
        this.fitsStarMinContrast = Math.max(this.starMinContrast, Math.min(255, fitsStarMinContrast));
        this.starContrastPercentile = clampPercentile(starContrastPercentile, 0.9985);
        this.fitsStarContrastPercentile = clampPercentile(fitsStarContrastPercentile, 0.9994);
        this.siteLatitudeDeg = siteLatitudeDeg;
        this.siteLongitudeDeg = siteLongitudeDeg;
        this.catalogPath = catalogPath == null ? "" : catalogPath;
        this.catalogMatchRadiusDeg = Math.max(0.001, catalogMatchRadiusDeg);
        this.onlineCatalogEnabled = onlineCatalogEnabled;
        this.onlineCatalogUrl = parseUri(onlineCatalogUrl).orElse(DEFAULT_ONLINE_CATALOG_URL);
        this.onlineCatalogCacheTtl = Duration.ofHours(Math.max(1, onlineCatalogCacheTtlHours));
        this.onlineCatalogMagnitudeLimit = Math.max(-2.0, Math.min(20.0, onlineCatalogMagnitudeLimit));
        this.onlineCatalogMaxRows = Math.max(100, onlineCatalogMaxRows);
        this.onlineCatalogTimeout = Duration.ofMillis(Math.max(500, onlineCatalogTimeoutMs));
        this.executor = Executors.newFixedThreadPool(Math.max(1, workerThreads));
        this.workDir = configuredWorkDir == null || configuredWorkDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "camserver-plate-solve")
                : Path.of(configuredWorkDir);
        this.onlineCatalogCacheFile = onlineCatalogCacheFile == null || onlineCatalogCacheFile.isBlank()
                ? this.workDir.resolve("catalog-cache").resolve("simbad-bright-stars.csv")
                : Path.of(onlineCatalogCacheFile);
    }

    public PlateSolveResult getStatus(long imgId) {
        PlateSolveResult cached = resultCache.get(imgId);
        if (cached != null) {
            return cached.withCached(true);
        }

        CompletableFuture<PlateSolveResult> job = runningJobs.get(imgId);
        if (job == null) {
            return statusOnly(imgId, PlateSolveStatus.NOT_STARTED, "Plate solve has not been started.");
        }

        if (!job.isDone()) {
            PlateSolveProgress progress = progressCache.get(imgId);
            return statusOnly(
                    imgId,
                    PlateSolveStatus.RUNNING,
                    progress == null ? "Plate solve is running." : progress.detail()
            );
        }

        return job.getNow(statusOnly(imgId, PlateSolveStatus.FAILED, "Plate solve did not produce a result."));
    }

    public PlateSolveResult start(long imgId, boolean force, boolean wait) {
        if (!force) {
            PlateSolveResult cached = resultCache.get(imgId);
            if (cached != null) {
                return cached.withCached(true);
            }
        } else {
            resultCache.remove(imgId);
            progressCache.remove(imgId);
        }

        updateProgress(imgId, "Queued", 2, "Plate solve has been queued.", List.of());

        CompletableFuture<PlateSolveResult> job = runningJobs.computeIfAbsent(imgId, id ->
                CompletableFuture.supplyAsync(() -> solve(id), executor)
                        .whenComplete((result, error) -> {
                            runningJobs.remove(id);
                            if (result != null) {
                                resultCache.put(id, result);
                            }
                        })
        );

        if (wait) {
            return job.join();
        }

        return statusOnly(imgId, PlateSolveStatus.QUEUED, "Plate solve has been queued.");
    }

    private PlateSolveResult solve(long imgId) {
        try {
            updateProgress(imgId, "Preparing", 5, "Creating solver workspace.", List.of());
            Files.createDirectories(workDir);
            Image image = imageService.findById(imgId);
            Path sourcePath = resolveImagePath(image);
            updateProgress(imgId, "Loading image", 8, "Reading source image: " + sourcePath.getFileName(), List.of());
            SourceFrame sourceFrame = loadAvailableSourceFrame(imgId, image, sourcePath);
            sourcePath = sourceFrame.sourcePath();
            BufferedImage source = sourceFrame.image();
            if (source == null) {
                return statusOnly(imgId, PlateSolveStatus.FAILED, "Image file could not be decoded.");
            }

            updateProgress(imgId, "Preprocessing", 14, "Cropping all-sky image and building ignore masks.", List.of());
            CropImage cropImage = cropUsefulArea(source, sourceFrame.cropThreshold());
            boolean[] ignoreMask = maskService.buildIgnoreMask(source, image, cropImage.crop(), sourcePath);
            writeCrop(imgId, applyIgnoreMask(cropImage.image(), ignoreMask));
            updateProgress(
                    imgId,
                    "Detecting stars",
                    24,
                    "Detecting local point sources from " + sourceFrame.sourceKind() + " pixels.",
                    List.of()
            );
            List<PlateSolveStar> detected = detectStars(
                    cropImage.image(),
                    cropImage.crop(),
                    ignoreMask,
                    sourceFrame.starMinContrast(),
                    sourceFrame.starContrastPercentile()
            );
            List<PlateSolveStar> stars = refineWithLinearPixels(detected, sourceFrame.linear(), ignoreMask, cropImage.crop());
            long refined = java.util.stream.IntStream.range(0, stars.size())
                    .filter(index -> stars.get(index) != detected.get(index)).count();
            String centroidNote = sourceFrame.linear() == null ? ""
                    : "Sub-pixel centroids measured on linear FITS pixels for " + refined + " of " + stars.size() + " detections.\n";
            if (!enabled || !lostSolver.isAvailable()) {
                String message = enabled ? "LOST is unavailable; check its executable and star database."
                        : "Plate solving is disabled.";
                updateProgress(imgId, "Solver unavailable", 100, message, List.of());
                return complete(imgId, PlateSolveStatus.SOLVER_UNAVAILABLE, message,
                        cropImage.crop(), unavailableSolution(image, null), stars);
            }
            Optional<PlateSolveResult> result = completeWithLost(
                    imgId, image, source, sourceFrame.cropThreshold(), cropImage.crop(), stars);
            if (result.isPresent()) return withSourceInfo(result.get(), sourceFrame, centroidNote);

            PlateSolveProgress lastProgress = progressCache.get(imgId);
            String detail = lastProgress == null ? "" : lastProgress.detail();
            List<String> log = lastProgress == null ? List.of() : lastProgress.logTail();
            String message = stars.size() < 6 ? "LOST needs at least six usable star detections."
                    : "LOST could not verify a solution for this image.";
            updateProgress(imgId, "Not solved", 100, message, log);
            return complete(imgId, PlateSolveStatus.FAILED, message, cropImage.crop(),
                    unavailableSolution(image, sourceFrame.sourceKind() + "\n" + detail + "\n" + String.join("\n", log)), stars);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return statusOnly(imgId, PlateSolveStatus.FAILED, "Plate solve interrupted.");
        } catch (Exception e) {
            updateProgress(imgId, "Failed", 100, e.getMessage(), List.of());
            return statusOnly(imgId, PlateSolveStatus.FAILED, e.getMessage());
        }
    }

    private Path resolveImagePath(Image image) {
        String imgPath = image.getImgPath();
        Path fileName = Path.of(imgPath).getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Image path is empty.");
        }

        String name = fileName.toString();
        List<String> candidates = new ArrayList<>();
        Optional<String> extension = fileExtension(name)
                .filter(this::isKnownImageExtension);
        String baseName = extension
                .map(value -> name.substring(0, name.length() - value.length()))
                .orElse(name);

        for (String fitsExtension : FITS_EXTENSIONS) {
            candidates.add(baseName + fitsExtension);
        }

        if (extension.isPresent()) {
            candidates.add(name);
        } else {
            candidates.add(baseName + ".jpg");
            candidates.add(baseName + ".jpeg");
            candidates.add(baseName + ".png");
            candidates.add(baseName);
        }

        List<String> distinctCandidates = candidates.stream().distinct().toList();
        for (String candidate : distinctCandidates) {
            Path resolved = imagePaths.resolve(candidate).normalize();
            if (Files.exists(resolved)) {
                return resolved;
            }
        }

        // The frame may be archived (.gz or Rice .fz); expand it to a temp file the solver tools can read.
        for (String candidate : distinctCandidates) {
            Optional<StoredImage> stored = archiveService.locate(candidate);
            if (stored.isPresent() && stored.get().compressed()) {
                try {
                    return archiveService.materialize(stored.get());
                } catch (IOException e) {
                    throw new IllegalArgumentException("Archived image could not be expanded: " + candidate, e);
                }
            }
        }

        throw new IllegalArgumentException("Image file was not found: " + fileName);
    }

    private SourceFrame loadSourceFrame(long imgId, Path sourcePath) throws IOException, InterruptedException {
        if (isFitsPath(sourcePath)) {
            updateProgress(imgId, "Loading FITS", 10, "Reading FITS source: " + sourcePath.getFileName(), List.of());
            FitsImage fits = readFits(imgId, sourcePath);
            return new SourceFrame(
                    fitsImageToBufferedImage(fits),
                    sourcePath,
                    "FITS",
                    fitsCropThreshold,
                    fitsStarMinContrast,
                    fitsStarContrastPercentile,
                    linearPixels(fits)
            );
        }

        BufferedImage source = ImageIO.read(sourcePath.toFile());
        return new SourceFrame(
                source,
                sourcePath,
                "JPG",
                cropThreshold,
                starMinContrast,
                starContrastPercentile,
                null
        );
    }

    private SourceFrame loadAvailableSourceFrame(long imgId, Image image, Path sourcePath) throws IOException, InterruptedException {
        try {
            return loadSourceFrame(imgId, sourcePath);
        } catch (IOException originalFailure) {
            if (!isFitsPath(sourcePath)) throw originalFailure;
            String name = Path.of(image.getImgPath()).getFileName().toString();
            Optional<String> extension = fileExtension(name).filter(this::isKnownImageExtension);
            String base = extension.map(value -> name.substring(0, name.length() - value.length())).orElse(name);
            for (String suffix : List.of(".jpg", ".jpeg", ".png")) {
                Path preview = imagePaths.resolve(base + suffix);
                if (!Files.isRegularFile(preview)) continue;
                try {
                    SourceFrame frame = loadSourceFrame(imgId, preview);
                    if (frame.image() != null) {
                        return new SourceFrame(frame.image(), preview,
                                "JPEG/PNG preview; original FITS unreadable: " + originalFailure.getMessage(),
                                frame.cropThreshold(), frame.starMinContrast(), frame.starContrastPercentile(), null);
                    }
                } catch (IOException previewFailure) {
                    originalFailure.addSuppressed(previewFailure);
                }
            }
            throw originalFailure;
        }
    }

    private PlateSolveResult withSourceInfo(PlateSolveResult result, SourceFrame frame, String centroidNote) {
        PlateSolveSolution solution = result.solution();
        var documented = new PlateSolveSolution(solution.solved(), solution.fieldCenterRaDeg(), solution.fieldCenterDecDeg(),
                solution.fieldWidthDeg(), solution.fieldHeightDeg(), solution.siteLatitudeDeg(), solution.siteLongitudeDeg(),
                solution.wcsFile(), "Source pixels: " + frame.sourceKind() + ".\n" + centroidNote + solution.solverLog());
        return new PlateSolveResult(result.imgId(), result.status(), result.message(), result.cached(), result.generatedAt(),
                result.crop(), documented, result.progress(), result.stars());
    }

    private FitsImage readFits(long imgId, Path sourcePath) throws IOException, InterruptedException {
        Optional<FitsImage> directImage = readFirstPlainFitsImage(sourcePath);
        if (directImage.isPresent()) {
            return directImage.get();
        }

        Optional<Integer> compressedHduIndex = firstCompressedFitsImageHdu(sourcePath);
        if (compressedHduIndex.isEmpty()) {
            throw new IOException("FITS file does not contain a readable image HDU.");
        }

        if (!isCommandAvailable(resolvedFitsImcopyCommand)) {
            throw new IOException("Compressed FITS image requires local imcopy, but it was not found: " + fitsImcopyCommand);
        }

        Path imageWorkDir = workDir.resolve(Long.toString(imgId));
        Files.createDirectories(imageWorkDir);
        Path extractedPath = imageWorkDir.resolve("source-from-fits.fits");
        Path logPath = imageWorkDir.resolve("imcopy.log");
        Files.deleteIfExists(extractedPath);
        Files.deleteIfExists(logPath);

        String hduSpecifier = sourcePath + "[" + compressedHduIndex.get() + "]";
        List<String> command = List.of(resolvedFitsImcopyCommand, hduSpecifier, extractedPath.toString());
        updateProgress(imgId, "Loading FITS", 11, "Extracting compressed FITS image: " + String.join(" ", command), List.of());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logPath.toFile())
                .start();
        boolean finished = process.waitFor(Math.min(timeoutSeconds, 30), TimeUnit.SECONDS);
        String log = Files.exists(logPath) ? Files.readString(logPath, StandardCharsets.UTF_8) : "";
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timed out while extracting compressed FITS image with imcopy.");
        }

        if (process.exitValue() != 0 || !Files.exists(extractedPath)) {
            throw new IOException("imcopy failed while extracting compressed FITS image: " + log);
        }

        return readFirstPlainFitsImage(extractedPath)
                .orElseThrow(() -> new IOException("Extracted FITS image could not be decoded."));
    }

    private LinearPixels linearPixels(FitsImage fits) {
        int plane = fits.width() * fits.height();
        if (fits.channels() == 1) {
            return new LinearPixels(fits.width(), fits.height(), fits.pixels());
        }
        // Colour frames: the summed channels keep every photon of a star's profile.
        float[] sum = new float[plane];
        for (int channel = 0; channel < fits.channels(); channel++) {
            for (int index = 0; index < plane; index++) {
                sum[index] += fits.pixels()[channel * plane + index];
            }
        }
        return new LinearPixels(fits.width(), fits.height(), sum);
    }

    private Optional<FitsImage> readFirstPlainFitsImage(Path fitsPath) throws IOException {
        byte[] bytes = Files.readAllBytes(fitsPath);
        int offset = 0;
        int hduIndex = 0;

        while (offset >= 0 && offset < bytes.length) {
            Optional<FitsHdu> parsed = parseFitsHdu(bytes, offset, hduIndex);
            if (parsed.isEmpty()) {
                break;
            }

            FitsHdu hdu = parsed.get();
            if (hdu.plainImage()) {
                return Optional.of(readFitsImage(bytes, hdu));
            }

            if (hdu.nextOffset() <= offset) {
                break;
            }
            offset = hdu.nextOffset();
            hduIndex++;
        }

        return Optional.empty();
    }

    private Optional<Integer> firstCompressedFitsImageHdu(Path fitsPath) throws IOException {
        byte[] bytes = Files.readAllBytes(fitsPath);
        int offset = 0;
        int hduIndex = 0;

        while (offset >= 0 && offset < bytes.length) {
            Optional<FitsHdu> parsed = parseFitsHdu(bytes, offset, hduIndex);
            if (parsed.isEmpty()) {
                break;
            }

            FitsHdu hdu = parsed.get();
            if (hdu.compressedImage()) {
                return Optional.of(hdu.index());
            }

            if (hdu.nextOffset() <= offset) {
                break;
            }
            offset = hdu.nextOffset();
            hduIndex++;
        }

        return Optional.empty();
    }

    private Optional<FitsHdu> parseFitsHdu(byte[] bytes, int offset, int hduIndex) {
        if (offset < 0 || offset + 80 > bytes.length) {
            return Optional.empty();
        }

        Map<String, String> header = new HashMap<>();
        int cursor = offset;
        boolean foundEnd = false;
        while (cursor + 80 <= bytes.length) {
            String card = new String(bytes, cursor, 80, StandardCharsets.US_ASCII);
            String key = card.substring(0, 8).trim();
            cursor += 80;

            if ("END".equals(key)) {
                foundEnd = true;
                break;
            }

            if (!key.isBlank() && card.length() > 10 && card.charAt(8) == '=') {
                header.put(key, cleanFitsHeaderValue(card.substring(10)));
            }
        }

        if (!foundEnd) {
            return Optional.empty();
        }

        int headerBytes = cursor - offset;
        int headerEnd = offset + paddedFitsSize(headerBytes);
        int bitpix = headerInt(header, "BITPIX", 8);
        int naxis = Math.max(0, headerInt(header, "NAXIS", 0));
        int[] axes = new int[naxis];
        long elementCount = 1;
        for (int axis = 0; axis < naxis; axis++) {
            axes[axis] = Math.max(0, headerInt(header, "NAXIS" + (axis + 1), 0));
            elementCount *= axes[axis];
        }
        if (naxis == 0) {
            elementCount = 0;
        }

        int bytesPerPixel = Math.max(0, Math.abs(bitpix) / 8);
        long dataBytes = elementCount * bytesPerPixel;
        long pcount = Math.max(0, headerLong(header, "PCOUNT", 0));
        long gcount = Math.max(1, headerLong(header, "GCOUNT", 1));
        dataBytes = dataBytes * gcount + pcount;
        int nextOffset = headerEnd + paddedFitsSize(dataBytes);
        String extension = header.getOrDefault("XTENSION", "");
        boolean table = extension.equalsIgnoreCase("BINTABLE") || extension.equalsIgnoreCase("TABLE");
        boolean compressedImage = headerBoolean(header, "ZIMAGE", false);
        boolean plainImage = !compressedImage && !table && naxis >= 2 && elementCount > 0 && bytesPerPixel > 0;

        return Optional.of(new FitsHdu(
                hduIndex,
                header,
                headerEnd,
                Math.max(0, nextOffset),
                bitpix,
                axes,
                headerDoubleValue(header, "BSCALE", 1.0),
                headerDoubleValue(header, "BZERO", 0.0),
                plainImage,
                compressedImage
        ));
    }

    private FitsImage readFitsImage(byte[] bytes, FitsHdu hdu) throws IOException {
        int width = hdu.axes()[0];
        int height = hdu.axes()[1];
        int channels = hdu.axes().length >= 3 ? Math.max(1, Math.min(3, hdu.axes()[2])) : 1;
        int bytesPerPixel = Math.abs(hdu.bitpix()) / 8;
        int planeSize = Math.multiplyExact(width, height);
        int pixelCount = Math.multiplyExact(planeSize, channels);
        long requiredBytes = (long) pixelCount * bytesPerPixel;
        if (hdu.dataOffset() + requiredBytes > bytes.length) {
            throw new IOException("FITS image data is shorter than its header declares.");
        }

        float[] pixels = new float[pixelCount];
        for (int index = 0; index < pixelCount; index++) {
            int byteOffset = hdu.dataOffset() + index * bytesPerPixel;
            pixels[index] = (float) (readFitsPixel(bytes, byteOffset, hdu.bitpix()) * hdu.bscale() + hdu.bzero());
        }

        return new FitsImage(width, height, channels, pixels);
    }

    private BufferedImage fitsImageToBufferedImage(FitsImage fitsImage) {
        int width = fitsImage.width();
        int height = fitsImage.height();
        int channels = fitsImage.channels();
        int planeSize = width * height;
        double[] lows = new double[channels];
        double[] highs = new double[channels];

        for (int channel = 0; channel < channels; channel++) {
            int offset = channel * planeSize;
            lows[channel] = sampledFitsPercentile(fitsImage.pixels(), offset, planeSize, 0.002);
            highs[channel] = sampledFitsPercentile(fitsImage.pixels(), offset, planeSize, 0.999);
            if (highs[channel] <= lows[channel]) {
                highs[channel] = lows[channel] + 1.0;
            }
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = row + x;
                int red = scaleFitsPixel(fitsImage.pixels()[pixel], lows[0], highs[0]);
                int green = channels > 1
                        ? scaleFitsPixel(fitsImage.pixels()[planeSize + pixel], lows[1], highs[1])
                        : red;
                int blue = channels > 2
                        ? scaleFitsPixel(fitsImage.pixels()[planeSize * 2 + pixel], lows[2], highs[2])
                        : green;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }

        return image;
    }

    private double sampledFitsPercentile(float[] pixels, int offset, int length, double percentile) {
        int stride = Math.max(1, length / 200_000);
        double[] sample = new double[(length + stride - 1) / stride];
        int count = 0;

        for (int index = 0; index < length; index += stride) {
            float value = pixels[offset + index];
            if (Float.isFinite(value)) {
                sample[count++] = value;
            }
        }

        if (count == 0) {
            return 0;
        }

        Arrays.sort(sample, 0, count);
        int percentileIndex = (int) Math.floor((count - 1) * Math.max(0, Math.min(1, percentile)));
        return sample[percentileIndex];
    }

    private int scaleFitsPixel(float value, double low, double high) {
        if (!Float.isFinite(value)) {
            return 0;
        }

        double scaled = (value - low) / Math.max(1.0, high - low);
        scaled = Math.max(0.0, Math.min(1.0, scaled));
        return (int) Math.round(scaled * 255.0);
    }

    private double readFitsPixel(byte[] bytes, int offset, int bitpix) throws IOException {
        return switch (bitpix) {
            case 8 -> bytes[offset] & 0xff;
            case 16 -> (short) (((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff));
            case 32 -> ((bytes[offset] & 0xff) << 24)
                    | ((bytes[offset + 1] & 0xff) << 16)
                    | ((bytes[offset + 2] & 0xff) << 8)
                    | (bytes[offset + 3] & 0xff);
            case -32 -> Float.intBitsToFloat(
                    ((bytes[offset] & 0xff) << 24)
                            | ((bytes[offset + 1] & 0xff) << 16)
                            | ((bytes[offset + 2] & 0xff) << 8)
                            | (bytes[offset + 3] & 0xff)
            );
            case -64 -> Double.longBitsToDouble(
                    ((long) (bytes[offset] & 0xff) << 56)
                            | ((long) (bytes[offset + 1] & 0xff) << 48)
                            | ((long) (bytes[offset + 2] & 0xff) << 40)
                            | ((long) (bytes[offset + 3] & 0xff) << 32)
                            | ((long) (bytes[offset + 4] & 0xff) << 24)
                            | ((long) (bytes[offset + 5] & 0xff) << 16)
                            | ((long) (bytes[offset + 6] & 0xff) << 8)
                            | (bytes[offset + 7] & 0xff)
            );
            default -> throw new IOException("Unsupported FITS BITPIX value: " + bitpix);
        };
    }

    private String cleanFitsHeaderValue(String rawValue) {
        String value = rawValue.split("/", 2)[0].trim();
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.trim();
    }

    private int headerInt(Map<String, String> header, String key, int fallback) {
        try {
            return Integer.parseInt(header.getOrDefault(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long headerLong(Map<String, String> header, String key, long fallback) {
        try {
            return Long.parseLong(header.getOrDefault(key, Long.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double headerDoubleValue(Map<String, String> header, String key, double fallback) {
        try {
            return Double.parseDouble(header.getOrDefault(key, Double.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean headerBoolean(Map<String, String> header, String key, boolean fallback) {
        String value = header.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.equalsIgnoreCase("T") || value.equalsIgnoreCase("TRUE");
    }

    private int paddedFitsSize(long size) {
        if (size <= 0) {
            return 0;
        }
        return (int) (((size + FITS_BLOCK_SIZE - 1) / FITS_BLOCK_SIZE) * FITS_BLOCK_SIZE);
    }

    private boolean isFitsPath(Path path) {
        return fileExtension(path.getFileName().toString())
                .map(FITS_EXTENSIONS::contains)
                .orElse(false);
    }

    private boolean isKnownImageExtension(String extension) {
        return FITS_EXTENSIONS.contains(extension) || RASTER_EXTENSIONS.contains(extension);
    }

    private Optional<String> fileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(fileName.substring(dot).toLowerCase(Locale.ROOT));
    }

    private CropImage cropUsefulArea(BufferedImage source, int threshold) {
        int width = source.getWidth();
        int height = source.getHeight();
        int step = Math.max(1, Math.min(width, height) / 1200);
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int luminance = luminance(source.getRGB(x, y));
                if (luminance > threshold) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            PlateSolveCrop crop = new PlateSolveCrop(0, 0, width, height, width, height);
            return new CropImage(toRgb(source), crop);
        }

        int margin = Math.max(8, Math.min(width, height) / 100);
        minX = Math.max(0, minX - margin);
        minY = Math.max(0, minY - margin);
        maxX = Math.min(width - 1, maxX + margin);
        maxY = Math.min(height - 1, maxY + margin);

        int cropWidth = maxX - minX + 1;
        int cropHeight = maxY - minY + 1;
        PlateSolveCrop crop = new PlateSolveCrop(minX, minY, cropWidth, cropHeight, width, height);
        BufferedImage cropped = toRgb(source.getSubimage(minX, minY, cropWidth, cropHeight));
        return new CropImage(cropped, crop);
    }

    private Path writeCrop(long imgId, BufferedImage cropped) throws IOException {
        Path imageWorkDir = workDir.resolve(Long.toString(imgId));
        Files.createDirectories(imageWorkDir);
        Path croppedPath = imageWorkDir.resolve("crop.jpg");
        ImageIO.write(cropped, "jpg", croppedPath.toFile());
        return croppedPath;
    }

    private BufferedImage applyIgnoreMask(BufferedImage image, boolean[] ignoreMask) {
        BufferedImage masked = toRgb(image);
        int width = masked.getWidth();
        int height = masked.getHeight();

        if (ignoreMask.length != width * height) {
            return masked;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (ignoreMask[y * width + x]) {
                    masked.setRGB(x, y, 0);
                }
            }
        }

        return masked;
    }

    private List<PlateSolveStar> detectStars(
            BufferedImage image,
            PlateSolveCrop crop,
            boolean[] ignoreMask,
            int minContrast,
            double contrastPercentile) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] gray = new int[width * height];
        long[] integral = new long[(width + 1) * (height + 1)];

        for (int y = 0; y < height; y++) {
            int row = y * width;
            long running = 0;
            for (int x = 0; x < width; x++) {
                int index = row + x;
                int value = ignoreMask.length == width * height && ignoreMask[index] ? 0 : luminance(image.getRGB(x, y));
                gray[index] = value;
                running += value;
                integral[(y + 1) * (width + 1) + x + 1] = integral[y * (width + 1) + x + 1] + running;
            }
        }

        int backgroundRadius = Math.max(6, Math.min(width, height) / 180);
        int[] background = new int[width * height];
        int[] contrast = new int[width * height];
        int[] histogram = new int[256];
        int usablePixelCount = 0;

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (ignoreMask.length == width * height && ignoreMask[row + x]) {
                    continue;
                }
                usablePixelCount++;
                int localMean = localMean(integral, width, height, x, y, backgroundRadius);
                background[row + x] = localMean;
                int value = Math.max(0, gray[row + x] - localMean);
                contrast[row + x] = value;
                histogram[Math.min(255, value)]++;
            }
        }

        int threshold = Math.max(minContrast, percentile(histogram, Math.max(1, usablePixelCount), contrastPercentile));
        boolean[] visited = new boolean[width * height];
        List<StarCandidate> candidates = new ArrayList<>();

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (visited[index]
                        || contrast[index] < threshold
                        || background[index] > maxStarBackground
                        || (ignoreMask.length == width * height && ignoreMask[index])) {
                    continue;
                }

                StarCandidate candidate = componentCandidate(gray, contrast, background, visited, width, height, x, y, threshold);
                if (candidate != null) {
                    candidates.add(refineCompactCentroid(candidate, gray, ignoreMask, width, height));
                }
            }
        }

        candidates = suppressDenseArtifacts(candidates);
        candidates = suppressLinearArtifacts(candidates);
        candidates.sort(Comparator.comparingInt(StarCandidate::brightness).reversed());
        List<PlateSolveStar> stars = new ArrayList<>();
        int minDistanceSq = 36;

        for (StarCandidate candidate : candidates) {
            if (stars.size() >= maxStars) {
                break;
            }

            boolean tooClose = stars.stream().anyMatch(star -> {
                double dx = star.cropX() - candidate.x();
                double dy = star.cropY() - candidate.y();
                return dx * dx + dy * dy < minDistanceSq;
            });

            if (tooClose) {
                continue;
            }

            int id = stars.size() + 1;
            stars.add(new PlateSolveStar(
                    id,
                    crop.x() + candidate.x(),
                    crop.y() + candidate.y(),
                    candidate.x(),
                    candidate.y(),
                    candidate.brightness(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    false
            ));
        }

        return stars;
    }

    private StarCandidate refineCompactCentroid(StarCandidate source, int[] gray, boolean[] ignoreMask, int width, int height) {
        // Detection thresholds clip a faint star's wings unevenly. Measure its position from
        // background-subtracted pixels instead. Keep saturated/extended sources unchanged.
        if (source.brightness() >= 240) return source;
        int cx = (int) Math.round(source.x()), cy = (int) Math.round(source.y());
        if (cx < 7 || cy < 7 || cx >= width - 7 || cy >= height - 7) return source;
        double[] ring = new double[225];
        int count = 0;
        for (int y = cy - 7; y <= cy + 7; y++) {
            for (int x = cx - 7; x <= cx + 7; x++) {
                if (ignoreMask.length == width * height && ignoreMask[y * width + x]) return source;
                double radius = Math.hypot(x - source.x(), y - source.y());
                if (radius > 5) ring[count++] = gray[y * width + x];
            }
        }
        double background = median(Arrays.copyOf(ring, count));
        double flux = 0, weightedX = 0, weightedY = 0, outsideFlux = 0;
        for (int y = cy - 5; y <= cy + 5; y++) {
            for (int x = cx - 5; x <= cx + 5; x++) {
                double radius = Math.hypot(x - source.x(), y - source.y());
                double weight = Math.max(0, gray[y * width + x] - background);
                if (radius <= 3) {
                    flux += weight;
                    weightedX += x * weight;
                    weightedY += y * weight;
                } else if (radius <= 5) outsideFlux += weight;
            }
        }
        if (flux == 0 || outsideFlux > flux * 0.5) return source;
        double x = weightedX / flux, y = weightedY / flux;
        if (Math.hypot(x - source.x(), y - source.y()) > 0.75) return source;
        return new StarCandidate(x, y, source.brightness(), source.background());
    }

    /**
     * Replaces 8-bit detection centroids with windowed centroids on the original linear FITS
     * pixels. The display stretch clips cores and quantises wings; a one-pixel component
     * otherwise reports its integer peak, up to half a pixel (about 130 arcsec) off.
     */
    private List<PlateSolveStar> refineWithLinearPixels(List<PlateSolveStar> stars, LinearPixels linear,
                                                        boolean[] ignoreMask, PlateSolveCrop crop) {
        if (linear == null || linear.width() != crop.originalWidth() || linear.height() != crop.originalHeight()) {
            return stars;
        }
        List<PlateSolveStar> refined = new ArrayList<>(stars.size());
        for (PlateSolveStar star : stars) {
            Optional<double[]> position = touchesMask(star, ignoreMask, crop) ? Optional.empty()
                    : linearCentroid(linear, star.x(), star.y());
            refined.add(position.map(p -> new PlateSolveStar(star.id(), p[0], p[1], p[0] - crop.x(), p[1] - crop.y(),
                    star.brightness(), star.raDeg(), star.decDeg(), star.name(), star.magnitude(),
                    star.catalogMatchDistanceArcsec(), star.identifiers(), star.links(), star.skyCoordinateSolved()))
                    .orElse(star));
        }
        return refined;
    }

    private boolean touchesMask(PlateSolveStar star, boolean[] ignoreMask, PlateSolveCrop crop) {
        if (ignoreMask.length != crop.width() * crop.height()) return false;
        int reach = LINEAR_CENTROID_HALF_WINDOW_PX + 2;
        int cx = (int) Math.round(star.cropX()), cy = (int) Math.round(star.cropY());
        for (int y = cy - reach; y <= cy + reach; y++) {
            for (int x = cx - reach; x <= cx + reach; x++) {
                if (x < 0 || y < 0 || x >= crop.width() || y >= crop.height() || ignoreMask[y * crop.width() + x]) return true;
            }
        }
        return false;
    }

    private Optional<double[]> linearCentroid(LinearPixels linear, double x0, double y0) {
        int width = linear.width(), height = linear.height(), outer = LINEAR_BACKGROUND_OUTER_PX;
        int half = LINEAR_CENTROID_HALF_WINDOW_PX;
        int cx = (int) Math.round(x0), cy = (int) Math.round(y0);
        if (!Double.isFinite(x0 + y0) || cx < outer + 1 || cy < outer + 1 || cx >= width - outer - 1 || cy >= height - outer - 1) {
            return Optional.empty();
        }
        float[] pixels = linear.values();
        double[] ring = new double[(2 * outer + 1) * (2 * outer + 1)];
        int count = 0;
        for (int y = cy - outer; y <= cy + outer; y++) {
            for (int x = cx - outer; x <= cx + outer; x++) {
                double radius = Math.hypot(x - cx, y - cy);
                float value = pixels[y * width + x];
                if (radius >= LINEAR_BACKGROUND_INNER_PX && radius <= outer && Float.isFinite(value)) ring[count++] = value;
            }
        }
        if (count == 0) return Optional.empty();
        double background = median(Arrays.copyOf(ring, count));
        double twoSigmaSq = 2 * LINEAR_CENTROID_SIGMA_PX * LINEAR_CENTROID_SIGMA_PX;
        double x = x0, y = y0;
        for (int iteration = 0; iteration < 20; iteration++) {
            int xi = (int) Math.round(x), yi = (int) Math.round(y);
            if (Math.abs(xi - cx) > outer - half || Math.abs(yi - cy) > outer - half) return Optional.empty();
            double sum = 0, sumX = 0, sumY = 0;
            for (int py = yi - half; py <= yi + half; py++) {
                for (int px = xi - half; px <= xi + half; px++) {
                    double value = pixels[py * width + px] - background;
                    if (!(value > 0)) continue;
                    double ex = px - x, ey = py - y;
                    double weight = value * Math.exp(-(ex * ex + ey * ey) / twoSigmaSq);
                    sum += weight;
                    sumX += weight * ex;
                    sumY += weight * ey;
                }
            }
            if (!(sum > 0)) return Optional.empty();
            double nextX = x + 2 * sumX / sum, nextY = y + 2 * sumY / sum;
            boolean converged = Math.abs(nextX - x) < 1e-4 && Math.abs(nextY - y) < 1e-4;
            x = nextX;
            y = nextY;
            if (converged) break;
        }
        if (Math.hypot(x - x0, y - y0) > LINEAR_CENTROID_MAX_SHIFT_PX) return Optional.empty();
        return Optional.of(new double[]{x, y});
    }

    private List<StarCandidate> suppressLinearArtifacts(List<StarCandidate> candidates) {
        if (candidates.size() < 8) {
            return candidates;
        }

        boolean[] rejected = new boolean[candidates.size()];
        suppressLinearArtifacts(candidates, rejected, true);
        suppressLinearArtifacts(candidates, rejected, false);

        List<StarCandidate> filtered = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!rejected[i]) {
                filtered.add(candidates.get(i));
            }
        }

        return filtered;
    }

    private List<StarCandidate> suppressDenseArtifacts(List<StarCandidate> candidates) {
        if (candidates.size() < 8) {
            return candidates;
        }

        double connectDistanceSq = Math.pow(Math.max(24.0, maxStarDiameter * 1.6), 2);
        boolean[] visited = new boolean[candidates.size()];
        boolean[] rejected = new boolean[candidates.size()];

        for (int i = 0; i < candidates.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = collectCandidateComponent(candidates, visited, i, connectDistanceSq);
            if (isDenseArtifact(candidates, component)) {
                for (int index : component) {
                    rejected[index] = true;
                }
            }
        }

        List<StarCandidate> filtered = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!rejected[i]) {
                filtered.add(candidates.get(i));
            }
        }

        return filtered;
    }

    private List<Integer> collectCandidateComponent(
            List<StarCandidate> candidates,
            boolean[] visited,
            int start,
            double connectDistanceSq) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        List<Integer> component = new ArrayList<>();
        queue.add(start);
        visited[start] = true;

        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            component.add(index);
            StarCandidate current = candidates.get(index);

            for (int next = 0; next < candidates.size(); next++) {
                if (visited[next]) {
                    continue;
                }

                StarCandidate candidate = candidates.get(next);
                double dx = current.x() - candidate.x();
                double dy = current.y() - candidate.y();
                if (dx * dx + dy * dy <= connectDistanceSq) {
                    visited[next] = true;
                    queue.add(next);
                }
            }
        }

        return component;
    }

    private boolean isDenseArtifact(List<StarCandidate> candidates, List<Integer> component) {
        if (component.size() < 6) {
            return false;
        }

        double meanX = 0;
        double meanY = 0;
        double meanBackground = 0;
        for (int index : component) {
            StarCandidate candidate = candidates.get(index);
            meanX += candidate.x();
            meanY += candidate.y();
            meanBackground += candidate.background();
        }
        meanX /= component.size();
        meanY /= component.size();
        meanBackground /= component.size();

        double covXX = 0;
        double covXY = 0;
        double covYY = 0;
        double maxDistanceSq = 0;
        double nearestDistanceSum = 0;

        for (int index : component) {
            StarCandidate candidate = candidates.get(index);
            double dx = candidate.x() - meanX;
            double dy = candidate.y() - meanY;
            covXX += dx * dx;
            covXY += dx * dy;
            covYY += dy * dy;
            maxDistanceSq = Math.max(maxDistanceSq, dx * dx + dy * dy);
            nearestDistanceSum += nearestDistance(candidates, component, index);
        }

        covXX /= component.size();
        covXY /= component.size();
        covYY /= component.size();

        double trace = covXX + covYY;
        double determinant = covXX * covYY - covXY * covXY;
        double discriminant = Math.max(0, trace * trace - 4 * determinant);
        double major = (trace + Math.sqrt(discriminant)) / 2.0;
        double minor = Math.max(0.001, (trace - Math.sqrt(discriminant)) / 2.0);
        double elongation = major / minor;
        double span = Math.sqrt(maxDistanceSq) * 2.0;
        double averageNearestDistance = nearestDistanceSum / component.size();

        return span >= Math.max(48.0, maxStarDiameter * 2.6)
                && averageNearestDistance <= Math.max(18.0, maxStarDiameter * 1.1)
                && (meanBackground >= 28 || elongation >= 5.0);
    }

    private double nearestDistance(List<StarCandidate> candidates, List<Integer> component, int index) {
        StarCandidate source = candidates.get(index);
        double nearest = Double.MAX_VALUE;

        for (int otherIndex : component) {
            if (otherIndex == index) {
                continue;
            }

            StarCandidate candidate = candidates.get(otherIndex);
            double dx = source.x() - candidate.x();
            double dy = source.y() - candidate.y();
            nearest = Math.min(nearest, Math.sqrt(dx * dx + dy * dy));
        }

        return nearest == Double.MAX_VALUE ? 0 : nearest;
    }

    private void suppressLinearArtifacts(List<StarCandidate> candidates, boolean[] rejected, boolean vertical) {
        double bandWidth = Math.max(10.0, maxStarDiameter * 0.8);
        double maxGap = Math.max(20.0, maxStarDiameter * 1.5);
        double minSpan = Math.max(54.0, maxStarDiameter * 3.0);
        int minRun = 6;

        for (int i = 0; i < candidates.size(); i++) {
            if (rejected[i]) {
                continue;
            }

            StarCandidate anchor = candidates.get(i);
            List<Integer> band = new ArrayList<>();
            for (int j = 0; j < candidates.size(); j++) {
                if (rejected[j]) {
                    continue;
                }

                StarCandidate candidate = candidates.get(j);
                double crossAxisDistance = vertical
                        ? Math.abs(candidate.x() - anchor.x())
                        : Math.abs(candidate.y() - anchor.y());
                if (crossAxisDistance <= bandWidth) {
                    band.add(j);
                }
            }

            if (band.size() < minRun) {
                continue;
            }

            band.sort(Comparator.comparingDouble(index -> vertical
                    ? candidates.get(index).y()
                    : candidates.get(index).x()));

            int runStart = 0;
            for (int runEnd = 1; runEnd <= band.size(); runEnd++) {
                boolean endOfRun = runEnd == band.size();
                if (!endOfRun) {
                    double previous = vertical
                            ? candidates.get(band.get(runEnd - 1)).y()
                            : candidates.get(band.get(runEnd - 1)).x();
                    double current = vertical
                            ? candidates.get(band.get(runEnd)).y()
                            : candidates.get(band.get(runEnd)).x();
                    endOfRun = current - previous > maxGap;
                }

                if (endOfRun) {
                    markLinearRun(candidates, band, rejected, runStart, runEnd, vertical, minRun, minSpan);
                    runStart = runEnd;
                }
            }
        }
    }

    private void markLinearRun(
            List<StarCandidate> candidates,
            List<Integer> band,
            boolean[] rejected,
            int runStart,
            int runEnd,
            boolean vertical,
            int minRun,
            double minSpan) {
        int runLength = runEnd - runStart;
        if (runLength < minRun) {
            return;
        }

        StarCandidate first = candidates.get(band.get(runStart));
        StarCandidate last = candidates.get(band.get(runEnd - 1));
        double span = vertical ? Math.abs(last.y() - first.y()) : Math.abs(last.x() - first.x());
        if (span < minSpan) {
            return;
        }

        for (int i = runStart; i < runEnd; i++) {
            rejected[band.get(i)] = true;
        }
    }

    private PlateSolveSolution unavailableSolution(Image image, String solverLog) {
        return new PlateSolveSolution(
                false,
                null,
                null,
                null,
                null,
                siteFor(image).latitudeDeg(),
                siteFor(image).longitudeDeg(),
                null,
                compactSolverLog(solverLog)
        );
    }

    private Optional<PlateSolveResult> completeWithLost(
            long imgId, Image image, BufferedImage source, int geometryThreshold,
            PlateSolveCrop crop, List<PlateSolveStar> detections) throws InterruptedException {
        if (!lostSolver.isAvailable() || detections.size() < 6) {
            return Optional.empty();
        }
        UndistortedSolveInput input = new UndistortedSolveInput(null,
                UNDISTORTED_SOLVE_SIZE_PX, UNDISTORTED_SOLVE_SIZE_PX, UNDISTORTED_SOLVE_FIELD_WIDTH_DEG);
        Optional<FisheyeLens> lens = FisheyeLens.find(image.getCameraId(), crop.originalWidth(), crop.originalHeight());
        java.util.function.Function<PlateSolveStar, Optional<LostPlateSolver.Centroid>> rectify;
        String calibrationLog;
        List<LostPlateSolver.Centroid> centroids;
        if (lens.isPresent()) {
            updateProgress(imgId, "LOST calibration", 28, "Correcting measured stars with the calibrated camera lens.", List.of());
            rectify = star -> lens.get().toPinhole(star.x(), star.y(), input.width(), input.fieldWidthDeg());
            centroids = selectLostSources(detections, source, rectify, input.width());
            calibrationLog = "Measured fisheye lens calibration for " + image.getCameraId()
                    + " (" + crop.originalWidth() + " x " + crop.originalHeight() + ").";
        } else {
            updateProgress(imgId, "LOST calibration", 28, "Estimating fisheye geometry for LOST.", List.of());
            Optional<AllSkyProjection> calibrated = estimateAllSkyProjection(image, source, geometryThreshold, crop, detections);
            if (calibrated.isEmpty()) return Optional.empty();
            AllSkyProjection projection = calibrated.get();
            // The generic horizontal projection is east-right, opposite to LOST's camera handedness.
            rectify = star -> pixelToHorizontal(star.x(), star.y(), projection)
                    .flatMap(horizontal -> horizontalToUndistortedPixel(horizontal, input))
                    .map(point -> new LostPlateSolver.Centroid(input.width() - 1 - point.x(), point.y()));
            centroids = selectUndistortedSources(detections, projection, input).stream()
                    .map(star -> new LostPlateSolver.Centroid(input.width() - star.x(), star.y() - 1)).toList();
            calibrationLog = "Coarse fisheye geometry used only to rectify measured LOST input.";
        }
        List<CatalogStar> catalog = catalogAtEpoch(image);
        updateProgress(imgId, "LOST Pyramid", 35,
                "Matching measured star patterns with LOST Pyramid and Davenport Q attitude estimation.", List.of());
        LostPlateSolver.Run run = lostSolver.solveVerified(workDir.resolve(Long.toString(imgId)), centroids,
                input.width(), input.height(), input.fieldWidthDeg(),
                attitude -> verifyLostAttitude(image, detections, rectify, input, attitude, catalog));
        if (run.attitude() == null) {
            updateProgress(imgId, "LOST not solved", 90, "LOST could not find an independently verified attitude.", tailLog(run.log(), 12));
            return Optional.empty();
        }
        run = refineAttitude(imgId, image, detections, rectify, input, run, catalog);

        LostPlateSolver.Coordinate center = run.attitude().pixelToSky(
                (input.width() - 1) / 2.0, (input.height() - 1) / 2.0,
                input.width(), input.height(), input.fieldWidthDeg());
        PlateSolveSolution solution = new PlateSolveSolution(true, center.raDeg(), center.decDeg(),
                input.fieldWidthDeg(), input.fieldWidthDeg(), siteFor(image).latitudeDeg(), siteFor(image).longitudeDeg(), null,
                compactSolverLog(appendSolverLog(calibrationLog, run.log())));
        if (!isPlausibleLostSolution(image, solution)) {
            updateProgress(imgId, "LOST not solved", 90, "LOST attitude disagrees with the expected zenith.", tailLog(run.log(), 12));
            return Optional.empty();
        }

        List<PlateSolveStar> identified = new ArrayList<>();
        Set<String> usedCatalogStars = new HashSet<>();
        for (PlateSolveStar star : detections) {
            Optional<LostPlateSolver.Centroid> point = rectify.apply(star);
            if (point.isEmpty()) {
                identified.add(star);
                continue;
            }
            LostPlateSolver.Coordinate sky = run.attitude().pixelToSky(point.get().x(), point.get().y(),
                    input.width(), input.height(), input.fieldWidthDeg());
            SkyCoordinate coordinate = new SkyCoordinate(sky.raDeg(), sky.decDeg());
            Optional<CatalogMatch> match = matchCatalog(catalog, coordinate, confirmedMatchRadiusDeg())
                    .filter(value -> usedCatalogStars.add(catalogCoordinateKey(value.raDeg(), value.decDeg())));
            identified.add(new PlateSolveStar(star.id(), star.x(), star.y(), star.cropX(), star.cropY(), star.brightness(),
                    sky.raDeg(), sky.decDeg(), match.map(CatalogMatch::name).orElse(null),
                    match.map(CatalogMatch::magnitude).orElse(null), match.map(CatalogMatch::distanceArcsec).orElse(null),
                    match.map(CatalogMatch::identifiers).orElse(List.of()),
                    match.map(CatalogMatch::links).orElseGet(() -> coordinateLinks(coordinate)), true));
        }
        long matches = reliableCatalogMatches(identified);
        // Require corroboration beyond the four stars that can seed a Pyramid attitude.
        if (matches < 6) {
            updateProgress(imgId, "LOST not solved", 90,
                    "LOST attitude had fewer than six distinct catalog confirmations.", tailLog(run.log(), 12));
            return Optional.empty();
        }
        String message = "LOST Pyramid plate solve completed with " + matches + " verified catalog stars.";
        updateProgress(imgId, "Solved", 100, message, tailLog(run.log(), 12));
        return Optional.of(complete(imgId, PlateSolveStatus.SOLVED, message, crop, solution, identified));
    }

    private String catalogCoordinateKey(double raDeg, double decDeg) {
        return Math.round(raDeg * 1000) + ":" + Math.round(decDeg * 1000);
    }

    /**
     * LOST's attitude comes from its own catalog and the rasterised Pyramid stars. Fit the
     * rotation directly to every consistent measured star instead, validated on held-out stars.
     */
    private LostPlateSolver.Run refineAttitude(long imgId, Image image, List<PlateSolveStar> detections,
            java.util.function.Function<PlateSolveStar, Optional<LostPlateSolver.Centroid>> rectify,
            UndistortedSolveInput input, LostPlateSolver.Run initial, List<CatalogStar> catalog) {
        List<VerifiedDetection> inliers = consistentMatches(initial.attitude(), detections, rectify, input, catalog);
        if (inliers.size() < 12) return initial;
        List<VerifiedDetection> training = new ArrayList<>();
        List<VerifiedDetection> heldOut = new ArrayList<>();
        for (int i = 0; i < inliers.size(); i++) {
            if (i % 3 == 0) heldOut.add(inliers.get(i)); else training.add(inliers.get(i));
        }
        String detail = "Adaptive outlier rejection: " + inliers.size() + " consistent stars; "
                + (detections.size() - inliers.size()) + " detections excluded from refinement; "
                + heldOut.size() + " stars held out for independent validation.";
        updateProgress(imgId, "Refining attitude", 70, detail, List.of());
        LostPlateSolver.Attitude candidate = fitAttitude(initial.attitude(), training, rectify, input);
        double before = heldOutError(initial.attitude(), heldOut, rectify, input);
        double after = heldOutError(candidate, heldOut, rectify, input);
        // Keep the verified original when the fit does not improve independent stars.
        if (!(after <= before) || !verifyLostAttitude(image, detections, rectify, input, candidate, catalog)) {
            return new LostPlateSolver.Run(initial.attitude(), initial.log() + "\n" + detail
                    + "\nRetained the original verified attitude; refinement did not improve held-out stars.");
        }
        // The held-out stars confirmed the fit: use every consistent star, re-associated once.
        LostPlateSolver.Attitude refined = fitAttitude(candidate, inliers, rectify, input);
        List<VerifiedDetection> rematched = consistentMatches(refined, detections, rectify, input, catalog);
        if (rematched.size() >= 12) refined = fitAttitude(refined, rematched, rectify, input);
        if (!verifyLostAttitude(image, detections, rectify, input, refined, catalog)) refined = candidate;
        return new LostPlateSolver.Run(refined, initial.log() + "\n" + detail + String.format(Locale.ROOT,
                "\nLeast-squares attitude accepted: held-out RMS %.1f -> %.1f arcsec; final fit to %d stars.\n",
                before, after, Math.max(rematched.size(), inliers.size())));
    }

    /** Distinct catalog associations within the confirmation radius, minus median/MAD outliers. */
    private List<VerifiedDetection> consistentMatches(LostPlateSolver.Attitude attitude, List<PlateSolveStar> detections,
            java.util.function.Function<PlateSolveStar, Optional<LostPlateSolver.Centroid>> rectify,
            UndistortedSolveInput input, List<CatalogStar> catalog) {
        Map<String, VerifiedDetection> distinct = new LinkedHashMap<>();
        for (PlateSolveStar star : detections) {
            var point = rectify.apply(star);
            if (point.isEmpty()) continue;
            var sky = attitude.pixelToSky(point.get().x(), point.get().y(), input.width(), input.height(), input.fieldWidthDeg());
            matchCatalog(catalog, new SkyCoordinate(sky.raDeg(), sky.decDeg()), confirmedMatchRadiusDeg())
                    .ifPresent(match -> distinct.merge(catalogCoordinateKey(match.raDeg(), match.decDeg()),
                            new VerifiedDetection(star, match), (a, b) -> a.match().distanceArcsec() <= b.match().distanceArcsec() ? a : b));
        }
        if (distinct.isEmpty()) return List.of();
        double median = median(distinct.values().stream().mapToDouble(value -> value.match().distanceArcsec()).toArray());
        double mad = median(distinct.values().stream().mapToDouble(value -> Math.abs(value.match().distanceArcsec() - median)).toArray());
        double cutoff = Math.min(confirmedMatchRadiusDeg() * 3600, Math.max(30, median + 3 * 1.4826 * mad));
        return distinct.values().stream().filter(value -> value.match().distanceArcsec() <= cutoff).toList();
    }

    private LostPlateSolver.Attitude fitAttitude(LostPlateSolver.Attitude start, List<VerifiedDetection> stars,
            java.util.function.Function<PlateSolveStar, Optional<LostPlateSolver.Centroid>> rectify, UndistortedSolveInput input) {
        List<double[]> rays = new ArrayList<>();
        List<double[]> sky = new ArrayList<>();
        for (VerifiedDetection value : stars) {
            var point = rectify.apply(value.star()).orElseThrow();
            rays.add(LostPlateSolver.Attitude.cameraRay(point.x(), point.y(), input.width(), input.height(), input.fieldWidthDeg()));
            double ra = Math.toRadians(value.match().raDeg()), dec = Math.toRadians(value.match().decDeg());
            sky.add(new double[]{Math.cos(dec) * Math.cos(ra), Math.cos(dec) * Math.sin(ra), Math.sin(dec)});
        }
        return start.fitTo(rays, sky);
    }

    private double heldOutError(LostPlateSolver.Attitude attitude, List<VerifiedDetection> heldOut,
            java.util.function.Function<PlateSolveStar, Optional<LostPlateSolver.Centroid>> rectify, UndistortedSolveInput input) {
        double squaredError = 0;
        for (VerifiedDetection value : heldOut) {
            var point = rectify.apply(value.star()).orElseThrow();
            var sky = attitude.pixelToSky(point.x(), point.y(), input.width(), input.height(), input.fieldWidthDeg());
            double error = angularDistanceDeg(sky.raDeg(), sky.decDeg(), value.match().raDeg(), value.match().decDeg()) * 3600;
            if (error > confirmedMatchRadiusDeg() * 3600) return Double.POSITIVE_INFINITY;
            squaredError += error * error;
        }
        return Math.sqrt(squaredError / heldOut.size());
    }

    private double median(double[] values) {
        Arrays.sort(values);
        return (values[(values.length - 1) / 2] + values[values.length / 2]) / 2;
    }

    private record VerifiedDetection(PlateSolveStar star, CatalogMatch match) { }

    private boolean verifyLostAttitude(Image image, List<PlateSolveStar> detections,
            java.util.function.Function<PlateSolveStar, Optional<LostPlateSolver.Centroid>> rectify,
            UndistortedSolveInput input, LostPlateSolver.Attitude attitude, List<CatalogStar> catalog) {
        var center = attitude.pixelToSky((input.width() - 1) / 2.0, (input.height() - 1) / 2.0,
                input.width(), input.height(), input.fieldWidthDeg());
        Optional<SkyCoordinate> expected = zenithCoordinate(image);
        if (expected.isPresent() && angularDistanceDeg(center.raDeg(), center.decDeg(),
                expected.get().raDeg(), expected.get().decDeg()) > 15) return false;
        Set<String> matches = new HashSet<>();
        for (PlateSolveStar star : detections) {
            var point = rectify.apply(star);
            if (point.isEmpty()) continue;
            var sky = attitude.pixelToSky(point.get().x(), point.get().y(), input.width(), input.height(), input.fieldWidthDeg());
            matchCatalog(catalog, new SkyCoordinate(sky.raDeg(), sky.decDeg()), confirmedMatchRadiusDeg())
                    .ifPresent(match -> matches.add(catalogCoordinateKey(match.raDeg(), match.decDeg())));
            if (matches.size() >= 6) return true;
        }
        return false;
    }

    private List<LostPlateSolver.Centroid> selectLostSources(List<PlateSolveStar> detections, BufferedImage source,
            java.util.function.Function<PlateSolveStar, Optional<LostPlateSolver.Centroid>> rectify, int size) {
        // Peak intensity saturates at 255 and otherwise sorts bright stars by scan line.
        // Background-subtracted aperture flux retains their measured brightness ordering.
        Map<Integer, Double> flux = new HashMap<>();
        detections.forEach(star -> flux.put(star.id(), apertureFlux(source, star.x(), star.y())));
        List<PlateSolveStar> sorted = detections.stream()
                .sorted(Comparator.comparingDouble((PlateSolveStar star) -> flux.get(star.id())).reversed()).toList();
        int[] cells = new int[36];
        List<LostPlateSolver.Centroid> selected = new ArrayList<>();
        for (PlateSolveStar star : sorted) {
            Optional<LostPlateSolver.Centroid> mapped = rectify.apply(star);
            if (mapped.isEmpty()) continue;
            var point = mapped.get();
            if (point.x() < 8 || point.y() < 8 || point.x() >= size - 8 || point.y() >= size - 8) continue;
            int cell = (int) (point.x() * 6 / size) + 6 * (int) (point.y() * 6 / size);
            if (cells[cell] >= 3 || selected.stream().anyMatch(other ->
                    Math.hypot(point.x() - other.x(), point.y() - other.y()) < 12)) continue;
            selected.add(point);
            cells[cell]++;
            if (selected.size() >= 80) break;
        }
        return selected;
    }

    private double apertureFlux(BufferedImage source, double x, double y) {
        int cx = (int) Math.round(x), cy = (int) Math.round(y);
        if (cx < 12 || cy < 12 || cx >= source.getWidth() - 12 || cy >= source.getHeight() - 12) return 0;
        int[] ring = new int[625];
        int count = 0;
        for (int dy = -12; dy <= 12; dy++) {
            for (int dx = -12; dx <= 12; dx++) {
                int r2 = dx * dx + dy * dy;
                if (r2 >= 64 && r2 <= 144) ring[count++] = luminance(source.getRGB(cx + dx, cy + dy));
            }
        }
        Arrays.sort(ring, 0, count);
        double background = (ring[(count - 1) / 2] + ring[count / 2]) / 2.0;
        double flux = 0;
        for (int dy = -5; dy <= 5; dy++) {
            for (int dx = -5; dx <= 5; dx++) {
                if (dx * dx + dy * dy <= 25) flux += Math.max(0, luminance(source.getRGB(cx + dx, cy + dy)) - background);
            }
        }
        return flux;
    }

    private boolean isPlausibleLostSolution(Image image, PlateSolveSolution solution) {
        if (solution.fieldCenterRaDeg() == null
                || solution.fieldCenterDecDeg() == null
                || solution.fieldWidthDeg() == null
                || solution.fieldHeightDeg() == null) {
            return false;
        }

        if (solution.fieldWidthDeg() < UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 0.8
                || solution.fieldWidthDeg() > UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 1.2
                || solution.fieldHeightDeg() < UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 0.8
                || solution.fieldHeightDeg() > UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 1.2) {
            return false;
        }

        Optional<SkyCoordinate> expectedCenter = zenithCoordinate(image);
        if (expectedCenter.isEmpty()) {
            return true;
        }

        double centerErrorDeg = angularDistanceDeg(
                solution.fieldCenterRaDeg(),
                solution.fieldCenterDecDeg(),
                expectedCenter.get().raDeg(),
                expectedCenter.get().decDeg()
        );
        return centerErrorDeg <= 15.0;
    }

    private double confirmedMatchRadiusDeg() {
        return Math.min(CONFIRMED_STAR_MAX_ERROR_DEG, catalogMatchRadiusDeg);
    }

    private long reliableCatalogMatches(List<PlateSolveStar> stars) {
        double maxErrorArcsec = confirmedMatchRadiusDeg() * 3600.0;
        return stars.stream()
                .filter(star -> star.catalogMatchDistanceArcsec() != null
                        && star.catalogMatchDistanceArcsec() <= maxErrorArcsec)
                .count();
    }

    private Optional<AllSkyProjection> estimateAllSkyProjection(
            Image image, BufferedImage source, int threshold, PlateSolveCrop crop, List<PlateSolveStar> stars) {
        Optional<Double> sidereal = localSiderealTimeDeg(image);
        if (sidereal.isEmpty() || stars.size() < 6) return Optional.empty();
        return fitAllSkyProjection(getCatalogStars(), stars, estimateAllSkyGeometry(source, crop, threshold),
                crop, sidereal.get(), siteFor(image).latitudeDeg());
    }

    private Optional<AllSkyProjection> fitAllSkyProjection(
            List<CatalogStar> catalog,
            List<PlateSolveStar> stars,
            AllSkyGeometry geometry,
            PlateSolveCrop crop,
            double siderealTimeDeg, double latitudeDeg) {
        List<PlateSolveStar> candidates = stars.stream()
                .filter(star -> distancePx(star.x(), star.y(), geometry.centerX(), geometry.centerY())
                        <= geometry.radius() * 0.92)
                .sorted(Comparator.comparingInt(PlateSolveStar::brightness).reversed())
                .limit(260)
                .toList();
        if (candidates.size() < 3) {
            return Optional.empty();
        }

        List<HorizontalCatalogStar> visibleStars = visibleCatalogStars(catalog, siderealTimeDeg, latitudeDeg).stream()
                .filter(star -> star.altitudeDeg() >= 18.0)
                .filter(star -> star.catalogStar().magnitude() == null || star.catalogStar().magnitude() <= 3.5)
                .limit(80)
                .toList();
        if (visibleStars.size() < 3) {
            return Optional.empty();
        }

        ProjectionScore best = null;
        for (double rotation = 0; rotation < 360; rotation += 2.0) {
            for (double radialPower : fisheyeRadialPowers(crop)) {
                ProjectionScore score = scoreProjection(geometry, crop, candidates, visibleStars, rotation, radialPower);
                if (best == null || score.score() > best.score()) {
                    best = score;
                }
            }
        }

        if (best != null) {
            double baseRotation = best.projection().rotationDeg();
            double baseRadialPower = best.projection().radialPower();
            for (double rotation = baseRotation - 2.0; rotation <= baseRotation + 2.0; rotation += 0.25) {
                for (double radialPower = baseRadialPower - 0.08; radialPower <= baseRadialPower + 0.08; radialPower += 0.02) {
                    ProjectionScore score = scoreProjection(
                            geometry,
                            crop,
                            candidates,
                            visibleStars,
                            normalizeDegrees(rotation),
                            clampRadialPower(radialPower)
                    );
                    if (score.score() > best.score()) {
                        best = score;
                    }
                }
            }
        }

        if (best == null || best.projection().matchedStars() < 3) {
            return Optional.empty();
        }

        double tolerancePx = allSkyMatchTolerancePx(geometry);
        if (best.projection().rmsErrorPx() > tolerancePx * 0.9) {
            return Optional.empty();
        }

        return Optional.of(best.projection());
    }

    private ProjectionScore scoreProjection(
            AllSkyGeometry geometry,
            PlateSolveCrop crop,
            List<PlateSolveStar> candidates,
            List<HorizontalCatalogStar> visibleStars,
            double rotationDeg,
            double radialPower) {
        double tolerancePx = allSkyMatchTolerancePx(geometry);
        boolean[] used = new boolean[candidates.size()];
        int matches = 0;
        double weightedScore = 0;
        double errorSq = 0;

        for (HorizontalCatalogStar catalogStar : visibleStars) {
            Optional<ProjectedPoint> projected = projectHorizontal(
                    geometry,
                    crop,
                    catalogStar.altitudeDeg(),
                    catalogStar.azimuthDeg(),
                    rotationDeg,
                    radialPower
            );
            if (projected.isEmpty()) {
                continue;
            }

            int bestIndex = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < candidates.size(); i++) {
                if (used[i]) {
                    continue;
                }

                PlateSolveStar candidate = candidates.get(i);
                double distance = distancePx(candidate.x(), candidate.y(), projected.get().x(), projected.get().y());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }

            if (bestIndex >= 0 && bestDistance <= tolerancePx) {
                used[bestIndex] = true;
                matches++;
                errorSq += bestDistance * bestDistance;
                double magnitude = catalogStar.catalogStar().magnitude() == null
                        ? 3.0
                        : catalogStar.catalogStar().magnitude();
                weightedScore += Math.max(0.5, 5.0 - magnitude) * (1.0 - bestDistance / tolerancePx);
            }
        }

        double rms = matches == 0 ? Double.MAX_VALUE : Math.sqrt(errorSq / matches);
        AllSkyProjection projection = new AllSkyProjection(
                geometry.centerX(),
                geometry.centerY(),
                geometry.radius(),
                normalizeDegrees(rotationDeg),
                radialPower,
                crop.originalWidth(),
                crop.originalHeight(),
                matches,
                rms
        );
        return new ProjectionScore(projection, matches * 1000.0 + weightedScore * 100.0 - rms * 10.0);
    }

    private List<HorizontalCatalogStar> visibleCatalogStars(List<CatalogStar> catalog, double siderealTimeDeg, double latitudeDeg) {
        List<HorizontalCatalogStar> visible = new ArrayList<>();
        for (CatalogStar star : catalog) {
            HorizontalCoordinate coordinate = skyToHorizontal(star.raDeg(), star.decDeg(), siderealTimeDeg, latitudeDeg);
            if (coordinate.altitudeDeg() > 0) {
                visible.add(new HorizontalCatalogStar(star, coordinate.altitudeDeg(), coordinate.azimuthDeg()));
            }
        }

        visible.sort(Comparator.comparingDouble(star -> star.catalogStar().magnitude() == null
                ? 99.0
                : star.catalogStar().magnitude()));
        return visible;
    }

    private AllSkyGeometry estimateAllSkyGeometry(BufferedImage source, PlateSolveCrop crop, int threshold) {
        int step = Math.max(1, Math.min(source.getWidth(), source.getHeight()) / 900);
        int minX = source.getWidth();
        int minY = source.getHeight();
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < source.getHeight(); y += step) {
            for (int x = 0; x < source.getWidth(); x += step) {
                if (luminance(source.getRGB(x, y)) > threshold) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            double centerX = crop.x() + crop.width() / 2.0;
            double centerY = crop.y() + crop.height() / 2.0;
            return new AllSkyGeometry(centerX, centerY, Math.min(crop.width(), crop.height()) / 2.0);
        }

        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;
        double radius = Math.min(maxX - minX + step, maxY - minY + step) / 2.0;
        return new AllSkyGeometry(centerX, centerY, Math.max(1.0, radius));
    }

    private Optional<ProjectedPoint> projectHorizontal(
            AllSkyGeometry geometry,
            PlateSolveCrop crop,
            double altitudeDeg,
            double azimuthDeg,
            double rotationDeg,
            double radialPower) {
        double zenithFraction = (90.0 - altitudeDeg) / 90.0;
        double radialFraction = zenithToFisheyeRadiusFraction(zenithFraction, radialPower);
        if (radialFraction < 0 || radialFraction > 0.94) {
            return Optional.empty();
        }

        double radius = geometry.radius() * radialFraction;
        double angleRad = (azimuthDeg + rotationDeg) * DEG_TO_RAD;
        double x = geometry.centerX() + radius * Math.sin(angleRad);
        double y = geometry.centerY() - radius * Math.cos(angleRad);
        if (x < crop.x() || x > crop.x() + crop.width() || y < crop.y() || y > crop.y() + crop.height()) {
            return Optional.empty();
        }

        return Optional.of(new ProjectedPoint(x, y));
    }

    private List<XySource> selectUndistortedSources(
            List<PlateSolveStar> stars,
            AllSkyProjection projection,
            UndistortedSolveInput undistortedInput) {
        int[] cellCounts = new int[UNDISTORTED_XYLIST_GRID * UNDISTORTED_XYLIST_GRID];
        List<XySource> sources = new ArrayList<>();
        double minDistanceSq = UNDISTORTED_XYLIST_MIN_DISTANCE_PX * UNDISTORTED_XYLIST_MIN_DISTANCE_PX;

        List<PlateSolveStar> sortedStars = stars.stream()
                .sorted(Comparator.comparingInt(PlateSolveStar::brightness).reversed())
                .toList();
        for (PlateSolveStar star : sortedStars) {
            if (sources.size() >= UNDISTORTED_XYLIST_MAX_STARS) {
                break;
            }

            Optional<HorizontalCoordinate> horizontal = pixelToHorizontal(star.x(), star.y(), projection);
            if (horizontal.isEmpty() || horizontal.get().altitudeDeg() < UNDISTORTED_XYLIST_MIN_ALTITUDE_DEG) {
                continue;
            }

            Optional<ProjectedPoint> point = horizontalToUndistortedPixel(horizontal.get(), undistortedInput);
            if (point.isEmpty()) {
                continue;
            }

            double x = point.get().x();
            double y = point.get().y();
            if (x < UNDISTORTED_XYLIST_MARGIN_PX
                    || x > undistortedInput.width() - UNDISTORTED_XYLIST_MARGIN_PX
                    || y < UNDISTORTED_XYLIST_MARGIN_PX
                    || y > undistortedInput.height() - UNDISTORTED_XYLIST_MARGIN_PX) {
                continue;
            }

            int cellX = Math.min(UNDISTORTED_XYLIST_GRID - 1, (int) (x / undistortedInput.width() * UNDISTORTED_XYLIST_GRID));
            int cellY = Math.min(UNDISTORTED_XYLIST_GRID - 1, (int) (y / undistortedInput.height() * UNDISTORTED_XYLIST_GRID));
            int cellIndex = cellY * UNDISTORTED_XYLIST_GRID + cellX;
            if (cellCounts[cellIndex] >= UNDISTORTED_XYLIST_MAX_PER_CELL) {
                continue;
            }

            boolean tooClose = sources.stream().anyMatch(source -> {
                double dx = source.x() - (x + 1.0);
                double dy = source.y() - (y + 1.0);
                return dx * dx + dy * dy < minDistanceSq;
            });
            if (tooClose) {
                continue;
            }

            sources.add(new XySource(x + 1.0, y + 1.0, Math.max(1.0, star.brightness())));
            cellCounts[cellIndex]++;
        }

        return sources;
    }

    private Optional<ProjectedPoint> horizontalToUndistortedPixel(
            HorizontalCoordinate coordinate,
            UndistortedSolveInput input) {
        double zenithAngleDeg = 90.0 - coordinate.altitudeDeg();
        if (zenithAngleDeg < 0 || zenithAngleDeg >= 90.0) {
            return Optional.empty();
        }

        double centerX = (input.width() - 1) / 2.0;
        double centerY = (input.height() - 1) / 2.0;
        double halfPlane = Math.tan(input.fieldWidthDeg() * DEG_TO_RAD / 2.0);
        double radius = Math.tan(zenithAngleDeg * DEG_TO_RAD);
        double azimuthRad = coordinate.azimuthDeg() * DEG_TO_RAD;
        double x = centerX + (radius * Math.sin(azimuthRad) / halfPlane) * centerX;
        double y = centerY - (radius * Math.cos(azimuthRad) / halfPlane) * centerY;
        if (x < 0 || x >= input.width() || y < 0 || y >= input.height()) {
            return Optional.empty();
        }

        return Optional.of(new ProjectedPoint(x, y));
    }

    private Optional<HorizontalCoordinate> pixelToHorizontal(double x, double y, AllSkyProjection projection) {
        double dx = x - projection.centerX();
        double dy = projection.centerY() - y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        double radialFraction = distance / projection.radius();
        if (radialFraction > 0.98) {
            return Optional.empty();
        }

        double altitudeDeg = 90.0 - fisheyeRadiusToZenithFraction(radialFraction, projection.radialPower()) * 90.0;
        double imageAngleDeg = normalizeDegrees(Math.atan2(dx, dy) * RAD_TO_DEG);
        double azimuthDeg = normalizeDegrees(imageAngleDeg - projection.rotationDeg());
        return Optional.of(new HorizontalCoordinate(altitudeDeg, azimuthDeg));
    }

    private HorizontalCoordinate skyToHorizontal(double raDeg, double decDeg, double siderealTimeDeg) {
        return skyToHorizontal(raDeg, decDeg, siderealTimeDeg, siteLatitudeDeg);
    }

    private HorizontalCoordinate skyToHorizontal(double raDeg, double decDeg, double siderealTimeDeg, double latitudeDeg) {
        double hourAngleRad = normalizeSignedDegrees(siderealTimeDeg - raDeg) * DEG_TO_RAD;
        double decRad = decDeg * DEG_TO_RAD;
        double latRad = latitudeDeg * DEG_TO_RAD;
        double sinAlt = Math.sin(decRad) * Math.sin(latRad)
                + Math.cos(decRad) * Math.cos(latRad) * Math.cos(hourAngleRad);
        double altitudeRad = Math.asin(Math.max(-1, Math.min(1, sinAlt)));
        double cosAlt = Math.max(1.0e-9, Math.cos(altitudeRad));
        double sinAz = -Math.cos(decRad) * Math.sin(hourAngleRad) / cosAlt;
        double cosAz = (Math.sin(decRad) - Math.sin(altitudeRad) * Math.sin(latRad))
                / (cosAlt * Math.cos(latRad));
        double azimuthDeg = normalizeDegrees(Math.atan2(sinAz, cosAz) * RAD_TO_DEG);
        return new HorizontalCoordinate(altitudeRad * RAD_TO_DEG, azimuthDeg);
    }

    private SkyCoordinate horizontalToSky(HorizontalCoordinate coordinate, double siderealTimeDeg) {
        double altitudeRad = coordinate.altitudeDeg() * DEG_TO_RAD;
        double azimuthRad = coordinate.azimuthDeg() * DEG_TO_RAD;
        double latRad = siteLatitudeDeg * DEG_TO_RAD;
        double sinDec = Math.sin(altitudeRad) * Math.sin(latRad)
                + Math.cos(altitudeRad) * Math.cos(latRad) * Math.cos(azimuthRad);
        double decRad = Math.asin(Math.max(-1, Math.min(1, sinDec)));
        double cosDec = Math.max(1.0e-9, Math.cos(decRad));
        double sinHourAngle = -Math.sin(azimuthRad) * Math.cos(altitudeRad) / cosDec;
        double cosHourAngle = (Math.sin(altitudeRad) - Math.sin(latRad) * Math.sin(decRad))
                / (Math.cos(latRad) * cosDec);
        double hourAngleDeg = Math.atan2(sinHourAngle, cosHourAngle) * RAD_TO_DEG;
        return new SkyCoordinate(normalizeDegrees(siderealTimeDeg - hourAngleDeg), decRad * RAD_TO_DEG);
    }

    private double allSkyMatchTolerancePx(AllSkyGeometry geometry) {
        return Math.max(18.0, geometry.radius() * 0.028);
    }

    private double[] fisheyeRadialPowers(PlateSolveCrop crop) {
        if (isQhy5iii678Frame(crop)) {
            return FISHEYE_RADIAL_POWERS;
        }

        return DEFAULT_RADIAL_POWERS;
    }

    private boolean isQhy5iii678Frame(PlateSolveCrop crop) {
        return Math.abs(crop.originalWidth() - QHY5III678_EFFECTIVE_WIDTH_PX) <= 16
                && Math.abs(crop.originalHeight() - QHY5III678_EFFECTIVE_HEIGHT_PX) <= 16
                && QHY5III678_PIXEL_SIZE_UM > 0;
    }

    private double zenithToFisheyeRadiusFraction(double zenithFraction, double radialPower) {
        return Math.pow(Math.max(0, Math.min(1, zenithFraction)), clampRadialPower(radialPower));
    }

    private double fisheyeRadiusToZenithFraction(double radialFraction, double radialPower) {
        return Math.pow(Math.max(0, Math.min(1, radialFraction)), 1.0 / clampRadialPower(radialPower));
    }

    private double clampRadialPower(double radialPower) {
        if (!Double.isFinite(radialPower)) {
            return 1.0;
        }
        return Math.max(0.55, Math.min(1.55, radialPower));
    }

    private double distancePx(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private String appendSolverLog(String solverLog, String message) {
        if (solverLog == null || solverLog.isBlank()) {
            return message;
        }
        return compactSolverLog(solverLog + "\n" + message);
    }

    private String compactSolverLog(String solverLog) {
        if (solverLog == null || solverLog.length() <= 20_000) {
            return solverLog;
        }

        return "[solver log trimmed to last 20000 characters]\n"
                + solverLog.substring(solverLog.length() - 20_000);
    }

    private PlateSolveResult complete(
            long imgId,
            PlateSolveStatus status,
            String message,
            PlateSolveCrop crop,
            PlateSolveSolution solution,
            List<PlateSolveStar> stars) {
        return new PlateSolveResult(
                imgId,
                status,
                message,
                false,
                Instant.now(),
                crop,
                solution,
                progressCache.get(imgId),
                stars
        );
    }

    private PlateSolveResult statusOnly(long imgId, PlateSolveStatus status, String message) {
        return new PlateSolveResult(
                imgId,
                status,
                message,
                false,
                Instant.now(),
                null,
                null,
                progressCache.get(imgId),
                List.of()
        );
    }

    private void updateProgress(long imgId, String phase, int percent, String detail, List<String> logTail) {
        PlateSolveProgress previous = progressCache.get(imgId);
        Instant startedAt = previous == null ? Instant.now() : previous.startedAt();
        progressCache.put(imgId, newProgress(phase, percent, detail, logTail, startedAt));
    }

    private PlateSolveProgress newProgress(
            String phase,
            int percent,
            String detail,
            List<String> logTail,
            Instant startedAt) {
        return new PlateSolveProgress(
                phase,
                Math.max(0, Math.min(100, percent)),
                detail == null || detail.isBlank() ? "Plate solve is running." : detail,
                logTail == null ? List.of() : List.copyOf(logTail),
                startedAt,
                Instant.now(),
                "LOST"
        );
    }

    private List<String> tailLog(String log, int maxLines) {
        if (log == null || log.isBlank() || maxLines <= 0) {
            return List.of();
        }

        String[] lines = log.replace("\r", "").split("\n");
        int start = Math.max(0, lines.length - maxLines);
        List<String> tail = new ArrayList<>();
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                tail.add(lines[i]);
            }
        }
        return tail;
    }

    private Optional<CatalogMatch> matchCatalog(List<CatalogStar> catalog, SkyCoordinate coordinate, double radiusDeg) {
        CatalogMatch best = null;

        for (CatalogStar star : catalog) {
            double distanceDeg = angularDistanceDeg(coordinate.raDeg(), coordinate.decDeg(), star.raDeg(), star.decDeg());
            if (distanceDeg > radiusDeg) {
                continue;
            }

            if (best == null || distanceDeg < best.distanceArcsec() / 3600.0) {
                best = new CatalogMatch(
                        star.name(),
                        star.magnitude(),
                        distanceDeg * 3600.0,
                        starIdentifiers(star),
                        starLinks(star),
                        star.raDeg(),
                        star.decDeg()
                );
            }
        }

        return Optional.ofNullable(best);
    }

    private List<CatalogStar> getCatalogStars() {
        List<CatalogStar> cached = catalogStars;
        if (cached != null) {
            return cached;
        }

        if (catalogPath.isBlank()) {
            catalogStars = List.of();
            return catalogStars;
        }

        List<CatalogStar> loaded = new ArrayList<>();
        try {
            List<String> lines = readCatalogLines();
            List<String> header = null;

            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split(",", -1);
                if (header == null && isCatalogHeader(parts)) {
                    header = Arrays.stream(parts)
                            .map(this::normalizeHeader)
                            .toList();
                    continue;
                }

                Optional<CatalogStar> star = header == null
                        ? parseLegacyCatalogStar(parts)
                        : parseHeaderCatalogStar(header, parts);
                star.ifPresent(loaded::add);
            }
        } catch (IOException ignored) {
            loaded = List.of();
        }

        List<CatalogStar> online = getOnlineCatalogStars();
        List<CatalogStar> merged = new ArrayList<>();
        for (CatalogStar star : loaded) merged.add(withOnlineProperMotion(star, online));
        merged.addAll(online);
        catalogStars = merged;
        return catalogStars;
    }

    /** Bundled named stars carry no proper motion; borrow it from the same SIMBAD star. */
    private CatalogStar withOnlineProperMotion(CatalogStar star, List<CatalogStar> online) {
        if (star.hasProperMotion()) return star;
        CatalogStar best = null;
        double bestDistance = 0.02;
        for (CatalogStar candidate : online) {
            if (!candidate.hasProperMotion() || (star.magnitude() != null && candidate.magnitude() != null
                    && Math.abs(star.magnitude() - candidate.magnitude()) > 0.5)) continue;
            double distance = angularDistanceDeg(star.raDeg(), star.decDeg(), candidate.raDeg(), candidate.decDeg());
            if (distance <= bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? star : star.withProperMotion(best.pmRaMasPerYear(), best.pmDecMasPerYear());
    }

    /** Catalog positions moved to the image's epoch; results are shared per 0.01-year bucket. */
    private List<CatalogStar> catalogAtEpoch(Image image) {
        List<CatalogStar> catalog = getCatalogStars();
        if (image.getTimestamp() == null) return catalog;
        double years = Math.round((image.getTimestamp().toEpochMilli() - J2000_EPOCH_MILLIS) / MILLIS_PER_JULIAN_YEAR * 100) / 100.0;
        EpochCatalog cached = epochCatalog;
        if (cached != null && cached.source() == catalog && cached.yearsSinceJ2000() == years) return cached.stars();
        List<CatalogStar> moved = catalog.stream().map(star -> star.atEpoch(years)).toList();
        epochCatalog = new EpochCatalog(catalog, years, moved);
        return moved;
    }

    private List<CatalogStar> getOnlineCatalogStars() {
        if (!onlineCatalogEnabled) {
            return List.of();
        }

        List<CatalogStar> cached = onlineCatalogStars;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (onlineCatalogStars != null) {
                return onlineCatalogStars;
            }

            List<CatalogStar> loaded = loadOnlineCatalogFromCache(false);
            // Caches written before proper motions were fetched are refreshed once; if SIMBAD is
            // unreachable the old positions are still used.
            if (loaded.isEmpty() || onlineCatalogCacheExpired() || !onlineCatalogCacheHasProperMotion()) {
                List<CatalogStar> refreshed = fetchOnlineCatalog();
                if (!refreshed.isEmpty()) {
                    loaded = refreshed;
                    writeOnlineCatalogCache(refreshed);
                } else if (loaded.isEmpty()) {
                    loaded = loadOnlineCatalogFromCache(true);
                }
            }

            onlineCatalogStars = loaded;
            return onlineCatalogStars;
        }
    }

    private boolean onlineCatalogCacheHasProperMotion() {
        try (var lines = Files.lines(onlineCatalogCacheFile, StandardCharsets.UTF_8)) {
            return lines.findFirst().map(header -> header.contains("pmra")).orElse(false);
        } catch (IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    private boolean onlineCatalogCacheExpired() {
        try {
            if (!Files.exists(onlineCatalogCacheFile)) {
                return true;
            }

            Instant modifiedAt = Files.getLastModifiedTime(onlineCatalogCacheFile).toInstant();
            return modifiedAt.plus(onlineCatalogCacheTtl).isBefore(Instant.now());
        } catch (IOException e) {
            return true;
        }
    }

    private List<CatalogStar> loadOnlineCatalogFromCache(boolean allowExpired) {
        try {
            if (!Files.exists(onlineCatalogCacheFile) || (!allowExpired && onlineCatalogCacheExpired())) {
                return List.of();
            }

            return parseOnlineCatalogCsv(Files.readString(onlineCatalogCacheFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<CatalogStar> fetchOnlineCatalog() {
        try {
            String query = "SELECT TOP " + onlineCatalogMaxRows
                    + " basic.oid,basic.main_id,basic.ra,basic.dec,basic.pmra,basic.pmdec,basic.otype,allfluxes.V "
                    + "FROM basic LEFT OUTER JOIN allfluxes ON basic.oid=allfluxes.oidref "
                    + "WHERE basic.ra IS NOT NULL AND basic.dec IS NOT NULL "
                    + "AND allfluxes.V IS NOT NULL AND allfluxes.V <= "
                    + String.format(Locale.ROOT, "%.2f", onlineCatalogMagnitudeLimit)
                    + " ORDER BY V ASC";
            String body = "REQUEST=doQuery"
                    + "&LANG=ADQL"
                    + "&FORMAT=csv"
                    + "&QUERY=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(onlineCatalogUrl)
                    .timeout(onlineCatalogTimeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().startsWith("<?xml")) {
                return List.of();
            }

            return parseOnlineCatalogCsv(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private void writeOnlineCatalogCache(List<CatalogStar> stars) {
        try {
            Path parent = onlineCatalogCacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            lines.add("name,ra,dec,mag,simbad_oid,otype,pmra,pmdec");
            for (CatalogStar star : stars) {
                lines.add(csvValue(star.name())
                        + "," + star.raDeg()
                        + "," + star.decDeg()
                        + "," + Objects.toString(star.magnitude(), "")
                        + "," + csvValue(star.identifiers().getOrDefault("SIMBAD OID", ""))
                        + "," + csvValue(star.objectType())
                        + "," + star.pmRaMasPerYear()
                        + "," + star.pmDecMasPerYear());
            }
            Files.write(onlineCatalogCacheFile, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private List<CatalogStar> parseOnlineCatalogCsv(String csv) {
        List<CatalogStar> stars = new ArrayList<>();
        List<String> header = null;

        for (String line : csv.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            List<String> parts = parseCsvLine(line);
            if (header == null) {
                header = parts.stream().map(this::normalizeHeader).toList();
                continue;
            }

            parseOnlineCatalogStar(header, parts).ifPresent(stars::add);
        }

        return stars;
    }

    private Optional<CatalogStar> parseOnlineCatalogStar(List<String> header, List<String> parts) {
        Optional<Double> ra = valueFor(header, parts, "ra").flatMap(this::parseDouble);
        Optional<Double> dec = valueFor(header, parts, "dec").flatMap(this::parseDouble);
        if (ra.isEmpty() || dec.isEmpty()) {
            return Optional.empty();
        }

        String objectType = valueFor(header, parts, "otype", "objecttype").orElse("");
        if (!isStellarObjectType(objectType)) {
            return Optional.empty();
        }

        String name = valueFor(header, parts, "mainid", "main_id", "name")
                .orElse("SIMBAD star")
                .replaceAll("\\s+", " ")
                .trim();
        Double magnitude = valueFor(header, parts, "v", "mag", "magnitude")
                .flatMap(this::parseDouble)
                .orElse(null);
        Map<String, String> identifiers = new LinkedHashMap<>();
        addIdentifier(identifiers, "SIMBAD", name);
        addIdentifier(identifiers, "SIMBAD OID", valueFor(header, parts, "oid", "simbadoid").orElse(""));
        addIdentifiersFromText(identifiers, name);
        double pmRa = valueFor(header, parts, "pmra").flatMap(this::parseDouble).orElse(0.0);
        double pmDec = valueFor(header, parts, "pmdec").flatMap(this::parseDouble).orElse(0.0);
        return Optional.of(new CatalogStar(name, ra.get(), dec.get(), magnitude, identifiers, "", objectType, pmRa, pmDec));
    }

    private boolean isStellarObjectType(String objectType) {
        return objectType != null && objectType.contains("*");
    }

    private List<String> readCatalogLines() throws IOException {
        if (catalogPath.startsWith("classpath:")) {
            String resourcePath = catalogPath.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                return List.of();
            }

            try (var inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .toList();
            }
        }

        Path path = Path.of(catalogPath);
        if (!Files.exists(path)) {
            return List.of();
        }

        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    private Optional<CatalogStar> parseLegacyCatalogStar(String[] parts) {
        if (parts.length < 3) {
            return Optional.empty();
        }

        Optional<Double> ra = parseDouble(parts[1].trim());
        Optional<Double> dec = parseDouble(parts[2].trim());
        if (ra.isEmpty() || dec.isEmpty()) {
            return Optional.empty();
        }

        String name = parts[0].trim();
        Double magnitude = parts.length >= 4 ? parseDouble(parts[3].trim()).orElse(null) : null;
        Map<String, String> identifiers = new LinkedHashMap<>();
        addIdentifiersFromText(identifiers, name);
        addIdentifier(identifiers, "HIP", part(parts, 4));
        addIdentifier(identifiers, "HD", part(parts, 5));
        addIdentifier(identifiers, "HR", part(parts, 6));
        addIdentifier(identifiers, "SAO", part(parts, 7));
        addIdentifier(identifiers, "Gaia DR3", part(parts, 8));
        addIdentifier(identifiers, "TYC", part(parts, 9));
        String wikipediaTitle = part(parts, 10);

        return Optional.of(new CatalogStar(name, ra.get(), dec.get(), magnitude, identifiers, wikipediaTitle, "", 0, 0));
    }

    private Optional<CatalogStar> parseHeaderCatalogStar(List<String> header, String[] parts) {
        Optional<Double> ra = valueFor(header, parts, "ra", "radeg", "rightascension", "rightascensiondeg")
                .flatMap(this::parseDouble);
        Optional<Double> dec = valueFor(header, parts, "dec", "decdeg", "declination", "declinationdeg")
                .flatMap(this::parseDouble);
        if (ra.isEmpty() || dec.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> identifiers = new LinkedHashMap<>();
        addIdentifier(identifiers, "HIP", valueFor(header, parts, "hip", "hipid", "hipparcos").orElse(""));
        addIdentifier(identifiers, "HD", valueFor(header, parts, "hd", "hdid", "henrydraper").orElse(""));
        addIdentifier(identifiers, "HR", valueFor(header, parts, "hr", "bs", "brightstar", "harvardrevised").orElse(""));
        addIdentifier(identifiers, "SAO", valueFor(header, parts, "sao").orElse(""));
        addIdentifier(identifiers, "Gaia DR3", valueFor(header, parts, "gaia", "gaiadr3", "dr3", "sourceid", "gaiasourceid").orElse(""));
        addIdentifier(identifiers, "TYC", valueFor(header, parts, "tyc", "tycho", "tycho2").orElse(""));

        String name = valueFor(header, parts, "name", "proper", "propername", "designation", "mainid")
                .orElseGet(() -> identifiers.isEmpty()
                        ? "Unknown star"
                        : identifierLabel(identifiers.entrySet().iterator().next().getKey(), identifiers.entrySet().iterator().next().getValue()));
        addIdentifiersFromText(identifiers, name);
        Double magnitude = valueFor(header, parts, "mag", "magnitude", "vmag", "visualmag", "photgmeanmag")
                .flatMap(this::parseDouble)
                .orElse(null);
        String wikipediaTitle = valueFor(header, parts, "wikipedia", "wikipediatitle", "wiki", "wikititle").orElse("");
        double pmRa = valueFor(header, parts, "pmra").flatMap(this::parseDouble).orElse(0.0);
        double pmDec = valueFor(header, parts, "pmdec").flatMap(this::parseDouble).orElse(0.0);

        return Optional.of(new CatalogStar(name, ra.get(), dec.get(), magnitude, identifiers, wikipediaTitle, "", pmRa, pmDec));
    }

    private boolean isCatalogHeader(String[] parts) {
        List<String> normalized = Arrays.stream(parts)
                .map(this::normalizeHeader)
                .toList();
        return normalized.stream().anyMatch(value -> value.equals("ra") || value.equals("radeg") || value.equals("rightascension"))
                && normalized.stream().anyMatch(value -> value.equals("dec") || value.equals("decdeg") || value.equals("declination"));
    }

    private Optional<String> valueFor(List<String> header, String[] parts, String... aliases) {
        for (String alias : aliases) {
            String normalizedAlias = normalizeHeader(alias);
            int index = header.indexOf(normalizedAlias);
            if (index >= 0 && index < parts.length && !parts[index].isBlank()) {
                return Optional.of(parts[index].trim());
            }
        }

        return Optional.empty();
    }

    private Optional<String> valueFor(List<String> header, List<String> parts, String... aliases) {
        for (String alias : aliases) {
            String normalizedAlias = normalizeHeader(alias);
            int index = header.indexOf(normalizedAlias);
            if (index >= 0 && index < parts.size() && !parts.get(index).isBlank()) {
                return Optional.of(parts.get(index).trim());
            }
        }

        return Optional.empty();
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        values.add(current.toString());
        return values;
    }

    private String csvValue(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String part(String[] parts, int index) {
        return index >= 0 && index < parts.length ? parts[index].trim() : "";
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void addIdentifiersFromText(Map<String, String> identifiers, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        Matcher matcher = IDENTIFIER_PATTERN.matcher(value);
        while (matcher.find()) {
            addIdentifier(identifiers, matcher.group(1).toUpperCase(), matcher.group(2));
        }

        Matcher gaiaMatcher = GAIA_IDENTIFIER_PATTERN.matcher(value);
        while (gaiaMatcher.find()) {
            addIdentifier(identifiers, "Gaia DR3", gaiaMatcher.group(1));
        }
    }

    private void addIdentifier(Map<String, String> identifiers, String catalog, String value) {
        String cleaned = cleanIdentifierValue(catalog, value);
        if (cleaned.isBlank() || cleaned.equals("0")) {
            return;
        }

        identifiers.putIfAbsent(catalog, cleaned);
    }

    private String cleanIdentifierValue(String catalog, String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.trim();
        if (cleaned.isBlank()) {
            return "";
        }

        if ("SIMBAD".equals(catalog)) {
            return cleaned;
        }

        for (String prefix : List.of(catalog, catalog.replace(" DR3", ""), "Gaia", "HIP", "HD", "HR", "SAO", "TYC")) {
            cleaned = cleaned.replaceFirst("(?i)^" + Pattern.quote(prefix) + "\\s*", "");
        }

        return cleaned.trim();
    }

    private List<PlateSolveStarIdentifier> starIdentifiers(CatalogStar star) {
        return star.identifiers().entrySet().stream()
                .map(entry -> {
                    String label = identifierLabel(entry.getKey(), entry.getValue());
                    return new PlateSolveStarIdentifier(
                            entry.getKey(),
                            entry.getValue(),
                            label,
                            simbadIdentifierUrl(label)
                    );
                })
                .toList();
    }

    private List<PlateSolveStarLink> starLinks(CatalogStar star) {
        List<PlateSolveStarLink> links = new ArrayList<>();
        String primaryIdentifier = star.identifiers().isEmpty()
                ? star.name()
                : identifierLabel(star.identifiers().entrySet().iterator().next().getKey(), star.identifiers().entrySet().iterator().next().getValue());

        if (star.wikipediaTitle() != null && !star.wikipediaTitle().isBlank()) {
            links.add(new PlateSolveStarLink("Wikipedia", wikipediaPageUrl(star.wikipediaTitle())));
        } else if (star.name() != null && !star.name().isBlank()) {
            links.add(new PlateSolveStarLink("Wikipedia", wikipediaSearchUrl(star.name())));
        } else if (!primaryIdentifier.isBlank()) {
            links.add(new PlateSolveStarLink("Wikipedia", wikipediaSearchUrl(primaryIdentifier)));
        }

        if (primaryIdentifier != null && !primaryIdentifier.isBlank()) {
            links.add(new PlateSolveStarLink("SIMBAD", simbadIdentifierUrl(primaryIdentifier)));
        }

        star.identifiers().entrySet().stream()
                .filter(entry -> entry.getKey().equals("HIP"))
                .findFirst()
                .ifPresent(entry -> links.add(new PlateSolveStarLink(
                        "VizieR HIP",
                        "https://vizier.cds.unistra.fr/viz-bin/VizieR-3?-source=I/239/hip_main&HIP=" + urlEncode(entry.getValue())
                )));

        return links;
    }

    private List<PlateSolveStarLink> coordinateLinks(SkyCoordinate coordinate) {
        String coordinates = String.format("%.6f %.6f", coordinate.raDeg(), coordinate.decDeg());
        return List.of(new PlateSolveStarLink(
                "SIMBAD coordinate search",
                "https://simbad.u-strasbg.fr/simbad/sim-coo?Coord=" + urlEncode(coordinates) + "&Radius=5&Radius.unit=arcsec"
        ));
    }

    private String identifierLabel(String catalog, String value) {
        if ("SIMBAD".equals(catalog)) {
            return value;
        }
        return catalog + " " + value;
    }

    private String simbadIdentifierUrl(String identifier) {
        return "https://simbad.u-strasbg.fr/simbad/sim-id?Ident=" + urlEncode(identifier) + "&NbIdent=1";
    }

    private String wikipediaPageUrl(String title) {
        return "https://en.wikipedia.org/wiki/" + urlEncode(title.trim().replace(' ', '_')).replace("+", "%20");
    }

    private String wikipediaSearchUrl(String query) {
        return "https://en.wikipedia.org/wiki/Special:Search?search=" + urlEncode(query);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String resolveSolverCommand(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }

        Path configuredPath = Path.of(command);
        if (configuredPath.isAbsolute() || command.contains("/")) {
            return command;
        }

        for (String pathEntry : commandSearchPaths()) {
            Path candidate = Path.of(pathEntry, command);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }

        return command;
    }

    private List<String> commandSearchPaths() {
        List<String> paths = new ArrayList<>();
        String environmentPath = System.getenv("PATH");
        if (environmentPath != null && !environmentPath.isBlank()) {
            paths.addAll(Arrays.asList(environmentPath.split(System.getProperty("path.separator"))));
        }
        paths.add("/opt/homebrew/bin");
        paths.add("/usr/local/bin");
        paths.add("/usr/bin");
        paths.add("/bin");
        return paths.stream()
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
    }

    private boolean isCommandAvailable(String command) {
        Path commandPath = Path.of(command);
        if (commandPath.isAbsolute()) {
            return Files.isExecutable(commandPath);
        }

        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }

        for (String entry : path.split(System.getProperty("path.separator"))) {
            if (Files.isExecutable(Path.of(entry, command))) {
                return true;
            }
        }

        return false;
    }

    private Optional<SkyCoordinate> zenithCoordinate(Image image) {
        return localSiderealTimeDeg(image)
                .map(siderealTimeDeg -> new SkyCoordinate(siderealTimeDeg, siteFor(image).latitudeDeg()));
    }

    private ObservingSite siteFor(Image image) {
        return ObservingSite.find(image.getCameraId())
                .orElseGet(() -> new ObservingSite("configured-default", siteLatitudeDeg, siteLongitudeDeg));
    }

    private Optional<Double> localSiderealTimeDeg(Image image) {
        if (image.getTimestamp() == null) {
            return Optional.empty();
        }

        double julianDate = image.getTimestamp().toEpochMilli() / 86_400_000.0 + 2_440_587.5;
        double daysSinceJ2000 = julianDate - 2_451_545.0;
        double centuriesSinceJ2000 = daysSinceJ2000 / 36_525.0;
        double gmstDeg = 280.46061837
                + 360.98564736629 * daysSinceJ2000
                + 0.000387933 * centuriesSinceJ2000 * centuriesSinceJ2000
                - centuriesSinceJ2000 * centuriesSinceJ2000 * centuriesSinceJ2000 / 38_710_000.0;
        return Optional.of(normalizeDegrees(gmstDeg + siteFor(image).longitudeDeg()));
    }

    private int luminance(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private int percentile(int[] histogram, int total, double percentile) {
        int target = (int) Math.ceil(total * percentile);
        int running = 0;

        for (int i = 0; i < histogram.length; i++) {
            running += histogram[i];
            if (running >= target) {
                return i;
            }
        }

        return histogram.length - 1;
    }

    private double clampPercentile(double value, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(0.5, Math.min(0.9999, value));
    }

    private int localMean(long[] integral, int width, int height, int x, int y, int radius) {
        int x1 = Math.max(0, x - radius);
        int y1 = Math.max(0, y - radius);
        int x2 = Math.min(width - 1, x + radius);
        int y2 = Math.min(height - 1, y + radius);
        int stride = width + 1;
        long sum = integral[(y2 + 1) * stride + x2 + 1]
                - integral[y1 * stride + x2 + 1]
                - integral[(y2 + 1) * stride + x1]
                + integral[y1 * stride + x1];
        int area = (x2 - x1 + 1) * (y2 - y1 + 1);
        return (int) (sum / Math.max(1, area));
    }

    private StarCandidate componentCandidate(
            int[] gray,
            int[] contrast,
            int[] background,
            boolean[] visited,
            int width,
            int height,
            int startX,
            int startY,
            int threshold) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startY * width + startX);
        visited[startY * width + startX] = true;

        int area = 0;
        int peak = 0;
        int minX = startX;
        int maxX = startX;
        int minY = startY;
        int maxY = startY;
        double weightedX = 0;
        double weightedY = 0;
        double totalWeight = 0;
        int backgroundSum = 0;

        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            int x = index % width;
            int y = index / width;
            int value = contrast[index];
            int weight = Math.max(1, value);
            area++;
            peak = Math.max(peak, gray[index]);
            backgroundSum += background[index];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            weightedX += x * weight;
            weightedY += y * weight;
            totalWeight += weight;

            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }

                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                        continue;
                    }

                    int neighbor = ny * width + nx;
                    if (!visited[neighbor] && contrast[neighbor] >= threshold) {
                        visited[neighbor] = true;
                        queue.add(neighbor);
                    }
                }
            }
        }

        int componentWidth = maxX - minX + 1;
        int componentHeight = maxY - minY + 1;
        double elongation = Math.max(componentWidth, componentHeight) / (double) Math.max(1, Math.min(componentWidth, componentHeight));
        if (area > maxStarArea
                || componentWidth > maxStarDiameter
                || componentHeight > maxStarDiameter
                || elongation > 3.0
                || totalWeight == 0) {
            return null;
        }

        return new StarCandidate(weightedX / totalWeight, weightedY / totalWeight, peak, backgroundSum / Math.max(1, area));
    }

    private double angularDistanceDeg(SkyCoordinate first, SkyCoordinate second) {
        return angularDistanceDeg(first.raDeg(), first.decDeg(), second.raDeg(), second.decDeg());
    }

    private double angularDistanceDeg(double ra1Deg, double dec1Deg, double ra2Deg, double dec2Deg) {
        double ra1 = ra1Deg * DEG_TO_RAD;
        double dec1 = dec1Deg * DEG_TO_RAD;
        double ra2 = ra2Deg * DEG_TO_RAD;
        double dec2 = dec2Deg * DEG_TO_RAD;
        double cosine = Math.sin(dec1) * Math.sin(dec2)
                + Math.cos(dec1) * Math.cos(dec2) * Math.cos(ra1 - ra2);
        return Math.acos(Math.max(-1, Math.min(1, cosine))) * RAD_TO_DEG;
    }

    private double normalizeDegrees(double value) {
        double normalized = value % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }

    private double normalizeSignedDegrees(double value) {
        double normalized = normalizeDegrees(value);
        return normalized > 180.0 ? normalized - 360.0 : normalized;
    }

    private boolean isLocalMaximum(int[] gray, int width, int x, int y, int value) {
        int index = y * width + x;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }

                if (gray[index + dy * width + dx] > value) {
                    return false;
                }
            }
        }

        return true;
    }

    private StarCandidate centroid(int[] gray, int width, int height, int x, int y, int threshold) {
        double weightedX = 0;
        double weightedY = 0;
        double totalWeight = 0;
        int peak = gray[y * width + x];

        for (int dy = -2; dy <= 2; dy++) {
            int cy = y + dy;
            if (cy < 0 || cy >= height) {
                continue;
            }

            for (int dx = -2; dx <= 2; dx++) {
                int cx = x + dx;
                if (cx < 0 || cx >= width) {
                    continue;
                }

                int value = gray[cy * width + cx];
                double weight = Math.max(0, value - threshold / 2.0);
                weightedX += cx * weight;
                weightedY += cy * weight;
                totalWeight += weight;
            }
        }

        if (totalWeight == 0) {
            return new StarCandidate(x, y, peak, 0);
        }

        return new StarCandidate(weightedX / totalWeight, weightedY / totalWeight, peak, 0);
    }

    private BufferedImage toRgb(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return rgb;
    }

    private Optional<Double> parseDouble(String value) {
        try {
            return Optional.of(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<URI> parseUri(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(URI.create(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record CropImage(BufferedImage image, PlateSolveCrop crop) {
    }

    private record SourceFrame(
            BufferedImage image,
            Path sourcePath,
            String sourceKind,
            int cropThreshold,
            int starMinContrast,
            double starContrastPercentile,
            LinearPixels linear) {
    }

    /** Channel-summed FITS values in original image coordinates; null for 8-bit sources. */
    private record LinearPixels(int width, int height, float[] values) {
    }

    private record FitsImage(int width, int height, int channels, float[] pixels) {
    }

    private record FitsHdu(
            int index,
            Map<String, String> header,
            int dataOffset,
            int nextOffset,
            int bitpix,
            int[] axes,
            double bscale,
            double bzero,
            boolean plainImage,
            boolean compressedImage) {
    }

    private record StarCandidate(double x, double y, int brightness, int background) {
    }

    private record SkyCoordinate(double raDeg, double decDeg) {
    }

    private record HorizontalCoordinate(double altitudeDeg, double azimuthDeg) {
    }

    private record HorizontalCatalogStar(CatalogStar catalogStar, double altitudeDeg, double azimuthDeg) {
    }

    private record ProjectedPoint(double x, double y) {
    }

    private record XySource(double x, double y, double flux) {
    }

    private record UndistortedSolveInput(Path path, int width, int height, double fieldWidthDeg) {
    }

    private record AllSkyGeometry(double centerX, double centerY, double radius) {
    }

    private record AllSkyProjection(
            double centerX,
            double centerY,
            double radius,
            double rotationDeg,
            double radialPower,
            int originalWidth,
            int originalHeight,
            int matchedStars,
            double rmsErrorPx) {
    }

    private record ProjectionScore(AllSkyProjection projection, double score) {
    }

    private record CatalogStar(
            String name,
            double raDeg,
            double decDeg,
            Double magnitude,
            Map<String, String> identifiers,
            String wikipediaTitle,
            String objectType,
            double pmRaMasPerYear,
            double pmDecMasPerYear) {
        boolean hasProperMotion() {
            return pmRaMasPerYear != 0 || pmDecMasPerYear != 0;
        }

        /** Linear propagation of the J2000 position; pmRA already includes cos(dec). */
        CatalogStar atEpoch(double yearsSinceJ2000) {
            if (!hasProperMotion() || yearsSinceJ2000 == 0) return this;
            double dec = decDeg + pmDecMasPerYear * yearsSinceJ2000 / 3_600_000.0;
            double cosDec = Math.max(1e-6, Math.cos(Math.toRadians(decDeg)));
            double ra = raDeg + pmRaMasPerYear * yearsSinceJ2000 / 3_600_000.0 / cosDec;
            return new CatalogStar(name, ((ra % 360) + 360) % 360, Math.max(-90, Math.min(90, dec)), magnitude,
                    identifiers, wikipediaTitle, objectType, pmRaMasPerYear, pmDecMasPerYear);
        }

        CatalogStar withProperMotion(double pmRa, double pmDec) {
            return new CatalogStar(name, raDeg, decDeg, magnitude, identifiers, wikipediaTitle, objectType, pmRa, pmDec);
        }
    }

    private record EpochCatalog(List<CatalogStar> source, double yearsSinceJ2000, List<CatalogStar> stars) {
    }

    private record CatalogMatch(
            String name,
            Double magnitude,
            double distanceArcsec,
            List<PlateSolveStarIdentifier> identifiers,
            List<PlateSolveStarLink> links,
            double raDeg,
            double decDeg) {
    }
}
