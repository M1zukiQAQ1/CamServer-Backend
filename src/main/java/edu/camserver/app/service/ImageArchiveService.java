package edu.camserver.app.service;

import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.archive.ArchiveFileInfo;
import edu.camserver.app.model.archive.ArchiveFileResult;
import edu.camserver.app.model.archive.ArchiveJob;
import edu.camserver.app.model.archive.ArchiveSelection;
import edu.camserver.app.model.archive.ArchiveStats;
import edu.camserver.app.model.archive.StoredImage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * On-demand gzip archive for the flat image directory.
 *
 * <p>A frame is stored either as {@code name.fits} or as {@code name.fits.gz}, never both for long.
 * {@link #locate} hides that from callers; {@link #compress}/{@link #decompress} switch a single
 * file between the two forms; {@link #startJob} does the same for a whole selection in the
 * background. Compressed output is verified (length + CRC32) before the original is removed.
 */
@Service
public class ImageArchiveService {
    private static final Logger log = LoggerFactory.getLogger(ImageArchiveService.class);
    private static final String GZ = ".gz";
    private static final int BUFFER = 256 * 1024;
    private static final int JOB_HISTORY = 20;
    private static final Duration STATS_TTL = Duration.ofMinutes(5);
    private static final Pattern FILE_TIMESTAMP =
            Pattern.compile("_(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?)\\.[A-Za-z0-9]+(?:\\.gz)?$");
    private static final Set<String> JPG_EXTENSIONS = Set.of(".jpg", ".jpeg");

    private final ImagePaths imagePaths;
    private final int gzipLevel;
    private final int minAgeMinutes;
    private final List<String> defaultExtensions;
    private final Path tempDir;
    private final ExecutorService jobRunner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-archive-job");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers;
    private final AtomicReference<ArchiveJob> activeJob = new AtomicReference<>();
    private final ConcurrentLinkedDeque<ArchiveJob> jobs = new ConcurrentLinkedDeque<>();
    private final Object statsLock = new Object();
    private volatile ArchiveStats cachedStats;

    public ImageArchiveService(
            ImagePaths imagePaths,
            @Value("${app.images.archive.gzip-level:6}") int gzipLevel,
            @Value("${app.images.archive.worker-threads:4}") int workerThreads,
            @Value("${app.images.archive.min-age-minutes:10}") int minAgeMinutes,
            @Value("${app.images.archive.extensions:.fits,.fit,.fts}") String extensions,
            @Value("${app.images.archive.temp-dir:}") String tempDir) {
        this.imagePaths = imagePaths;
        this.gzipLevel = Math.max(1, Math.min(9, gzipLevel));
        this.minAgeMinutes = Math.max(0, minAgeMinutes);
        this.defaultExtensions = normalizeExtensions(Arrays.asList(extensions.split(",")));
        this.tempDir = tempDir == null || tempDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "camserver-archive")
                : Path.of(tempDir);
        int threads = Math.max(1, workerThreads);
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "image-archive-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        ArchiveJob job = activeJob.get();
        if (job != null) {
            job.requestCancel();
        }
        jobRunner.shutdownNow();
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------ lookup

    /**
     * Finds the on-disk form of a requested file. The request may name the plain file or its
     * .gz form; whichever exists is returned, preferring the exact form asked for.
     */
    public Optional<StoredImage> locate(String requestedName) {
        String logicalName = logicalName(requestedName);
        Path plain = imagePaths.resolve(logicalName);
        Path gz = imagePaths.resolve(logicalName + GZ);
        boolean wantsGz = requestedName.toLowerCase(Locale.ROOT).endsWith(GZ);

        Path first = wantsGz ? gz : plain;
        Path second = wantsGz ? plain : gz;
        for (Path candidate : List.of(first, second)) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Optional.of(new StoredImage(logicalName, candidate, candidate == gz, Files.size(candidate)));
                } catch (IOException e) {
                    log.warn("Cannot stat {}: {}", candidate, e.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    public ArchiveFileInfo describe(String requestedName) {
        String logicalName = logicalName(requestedName);
        Optional<StoredImage> stored = locate(requestedName);
        if (stored.isEmpty()) {
            return new ArchiveFileInfo(logicalName, false, false, null, 0, null);
        }
        StoredImage image = stored.get();
        Instant modified = null;
        try {
            modified = Files.getLastModifiedTime(image.path()).toInstant();
        } catch (IOException ignored) {
            // informational only
        }
        return new ArchiveFileInfo(
                logicalName, true, image.gzipped(), image.path().getFileName().toString(), image.size(), modified);
    }

    /** Opens the file for reading with any gzip layer removed. */
    public InputStream openDecompressed(StoredImage stored) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(stored.path()), BUFFER);
        return stored.gzipped() ? new GZIPInputStream(raw, BUFFER) : raw;
    }

    /** Streams the file gzip-compressed, compressing on the fly when it is stored plain. */
    public void writeGzipped(StoredImage stored, OutputStream out) throws IOException {
        if (stored.gzipped()) {
            Files.copy(stored.path(), out);
            return;
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(stored.path()), BUFFER);
             GZIPOutputStream gzOut = new LevelGzipOutputStream(out, gzipLevel)) {
            in.transferTo(gzOut);
        }
    }

    /**
     * Returns a path that external tools can read directly. Plain files are returned as-is;
     * compressed files are expanded into the temp directory (reused while still current).
     */
    public Path materialize(StoredImage stored) throws IOException {
        if (!stored.gzipped()) {
            return stored.path();
        }
        Files.createDirectories(tempDir);
        Path target = tempDir.resolve(stored.logicalName());
        FileTime sourceTime = Files.getLastModifiedTime(stored.path());
        if (Files.isRegularFile(target) && Files.getLastModifiedTime(target).compareTo(sourceTime) >= 0) {
            return target;
        }
        Path tmp = tempDir.resolve(stored.logicalName() + ".tmp-" + System.nanoTime());
        try (InputStream in = openDecompressed(stored);
             OutputStream out = new BufferedOutputStream(
                     Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER)) {
            in.transferTo(out);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.setLastModifiedTime(tmp, sourceTime);
        moveReplacing(tmp, target);
        return target;
    }

    public MediaType mediaTypeFor(String logicalName) {
        String ext = extensionOf(logicalName);
        return switch (ext) {
            case ".fits", ".fit", ".fts" -> MediaType.parseMediaType("application/fits");
            case ".jpg", ".jpeg" -> MediaType.IMAGE_JPEG;
            case ".png" -> MediaType.IMAGE_PNG;
            case ".csv" -> MediaType.parseMediaType("text/csv");
            case ".json" -> MediaType.APPLICATION_JSON;
            case ".txt", ".log" -> MediaType.TEXT_PLAIN;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    // ------------------------------------------------------------ single files

    public ArchiveFileResult compress(String requestedName, boolean dryRun) throws IOException {
        String logicalName = logicalName(requestedName);
        Path plain = imagePaths.resolve(logicalName);
        Path gz = imagePaths.resolve(logicalName + GZ);
        if (!Files.isRegularFile(plain)) {
            if (Files.isRegularFile(gz)) {
                return ArchiveFileResult.skipped(logicalName, "compress", Files.size(gz), "already compressed");
            }
            throw new java.io.FileNotFoundException(logicalName);
        }
        long size = Files.size(plain);
        if (Files.isRegularFile(gz)) {
            return ArchiveFileResult.skipped(logicalName, "compress", size,
                    "both plain and .gz exist; decompress or remove one manually");
        }
        if (size == 0) {
            return ArchiveFileResult.skipped(logicalName, "compress", 0, "empty file");
        }
        if (isTooRecent(plain)) {
            return ArchiveFileResult.skipped(logicalName, "compress", size,
                    "modified less than " + minAgeMinutes + " minutes ago");
        }
        if (dryRun) {
            return new ArchiveFileResult(logicalName, "compress", true, size, size, "dry run");
        }
        long after = compressFile(plain, gz, size);
        return new ArchiveFileResult(logicalName, "compress", true, size, after, "compressed");
    }

    public ArchiveFileResult decompress(String requestedName, boolean dryRun) throws IOException {
        String logicalName = logicalName(requestedName);
        Path plain = imagePaths.resolve(logicalName);
        Path gz = imagePaths.resolve(logicalName + GZ);
        if (!Files.isRegularFile(gz)) {
            if (Files.isRegularFile(plain)) {
                return ArchiveFileResult.skipped(logicalName, "decompress", Files.size(plain), "already plain");
            }
            throw new java.io.FileNotFoundException(logicalName);
        }
        long size = Files.size(gz);
        if (Files.isRegularFile(plain)) {
            return ArchiveFileResult.skipped(logicalName, "decompress", size,
                    "both plain and .gz exist; compress or remove one manually");
        }
        if (dryRun) {
            return new ArchiveFileResult(logicalName, "decompress", true, size, size, "dry run");
        }
        long after = decompressFile(gz, plain);
        return new ArchiveFileResult(logicalName, "decompress", true, size, after, "decompressed");
    }

    private long compressFile(Path plain, Path gz, long size) throws IOException {
        Path tmp = plain.resolveSibling(gz.getFileName() + ".tmp-" + System.nanoTime());
        CRC32 sourceCrc = new CRC32();
        try {
            try (InputStream in = new CheckedInputStream(
                         new BufferedInputStream(Files.newInputStream(plain), BUFFER), sourceCrc);
                 OutputStream out = new LevelGzipOutputStream(new BufferedOutputStream(
                         Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER),
                         gzipLevel)) {
                in.transferTo(out);
            }

            CRC32 roundTripCrc = new CRC32();
            long roundTripLength;
            try (InputStream in = new CheckedInputStream(
                    new GZIPInputStream(new BufferedInputStream(Files.newInputStream(tmp), BUFFER), BUFFER),
                    roundTripCrc)) {
                roundTripLength = in.transferTo(OutputStream.nullOutputStream());
            }
            if (roundTripLength != size || roundTripCrc.getValue() != sourceCrc.getValue()) {
                throw new IOException("gzip verification failed for " + plain.getFileName()
                        + " (length " + roundTripLength + "/" + size + ")");
            }

            Files.setLastModifiedTime(tmp, Files.getLastModifiedTime(plain));
            moveReplacing(tmp, gz);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.delete(plain);
        return Files.size(gz);
    }

    private long decompressFile(Path gz, Path plain) throws IOException {
        Path tmp = plain.resolveSibling(plain.getFileName() + ".tmp-" + System.nanoTime());
        try {
            // GZIPInputStream checks the trailer CRC32 and length, so a clean read is a verified read.
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(gz), BUFFER), BUFFER);
                 OutputStream out = new BufferedOutputStream(
                         Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER)) {
                in.transferTo(out);
            }
            Files.setLastModifiedTime(tmp, Files.getLastModifiedTime(gz));
            moveReplacing(tmp, plain);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.delete(gz);
        return Files.size(plain);
    }

    // ------------------------------------------------------------------- jobs

    public ArchiveJob startJob(ArchiveJob.Type type, ArchiveSelection selection) {
        Objects.requireNonNull(selection, "selection");
        if (!selection.hasSelector()) {
            throw new IllegalArgumentException(
                    "Selection is empty: set names, prefix, olderThanDays, before/after, or all=true");
        }
        ArchiveJob job = new ArchiveJob(type, selection);
        if (!activeJob.compareAndSet(null, job)) {
            throw new IllegalStateException("An archive job is already running: " + activeJob.get().getId());
        }
        jobs.addFirst(job);
        while (jobs.size() > JOB_HISTORY) {
            jobs.pollLast();
        }
        jobRunner.submit(() -> runJob(job));
        return job;
    }

    public Optional<ArchiveJob> activeJob() {
        return Optional.ofNullable(activeJob.get());
    }

    public List<ArchiveJob> jobs() {
        return new ArrayList<>(jobs);
    }

    public Optional<ArchiveJob> job(String id) {
        return jobs.stream().filter(j -> j.getId().equals(id)).findFirst();
    }

    public boolean cancel(String id) {
        Optional<ArchiveJob> job = job(id);
        if (job.isEmpty() || !job.get().isActive()) {
            return false;
        }
        job.get().requestCancel();
        return true;
    }

    private void runJob(ArchiveJob job) {
        job.markRunning();
        try {
            List<Path> candidates = selectCandidates(job.getType(), job.getSelection());
            job.setCandidates(candidates.size());
            log.info("Archive job {} ({}): {} candidate file(s){}", job.getId(), job.getType(),
                    candidates.size(), job.isDryRun() ? " [dry run]" : "");

            List<CompletableFuture<Void>> futures = new ArrayList<>(candidates.size());
            for (Path candidate : candidates) {
                futures.add(CompletableFuture.runAsync(() -> processCandidate(job, candidate), workers));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            if (job.isCancelRequested()) {
                job.finish(ArchiveJob.State.CANCELLED, "Cancelled after " + job.getProcessed() + " of "
                        + job.getCandidates() + " files");
            } else {
                job.finish(ArchiveJob.State.COMPLETED, summary(job));
            }
            log.info("Archive job {} {}: {}", job.getId(), job.getState(), job.getMessage());
            invalidateStats();
        } catch (Exception e) {
            log.error("Archive job {} failed", job.getId(), e);
            job.finish(ArchiveJob.State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            activeJob.compareAndSet(job, null);
        }
    }

    private void processCandidate(ArchiveJob job, Path candidate) {
        if (job.isCancelRequested()) {
            return;
        }
        String name = candidate.getFileName().toString();
        job.setCurrentFile(name);
        try {
            ArchiveFileResult result = job.getType() == ArchiveJob.Type.COMPRESS
                    ? compress(name, job.isDryRun())
                    : decompress(name, job.isDryRun());
            job.recordResult(result);
        } catch (Exception e) {
            log.warn("Archive job {}: {} failed: {}", job.getId(), name, e.toString());
            job.recordError(name, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String summary(ArchiveJob job) {
        String verb = job.getType() == ArchiveJob.Type.COMPRESS ? "compressed" : "decompressed";
        String prefix = job.isDryRun() ? "Dry run: would have " : "";
        return prefix + verb + " " + job.getSucceeded() + " file(s), skipped " + job.getSkipped()
                + ", failed " + job.getFailed() + "; " + humanBytes(job.getBytesBefore()) + " -> "
                + humanBytes(job.getBytesAfter());
    }

    private List<Path> selectCandidates(ArchiveJob.Type type, ArchiveSelection selection) throws IOException {
        List<String> extensions = selection.getExtensions() == null || selection.getExtensions().isEmpty()
                ? defaultExtensions
                : normalizeExtensions(selection.getExtensions());
        boolean wantGz = type == ArchiveJob.Type.DECOMPRESS;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime before = selection.getBefore();
        if (selection.getOlderThanDays() != null) {
            LocalDateTime cutoff = now.minusDays(selection.getOlderThanDays());
            before = before == null || cutoff.isBefore(before) ? cutoff : before;
        }
        String prefix = selection.getPrefix() == null ? null : selection.getPrefix().trim();

        List<Path> matches = new ArrayList<>();
        if (selection.getNames() != null && !selection.getNames().isEmpty()) {
            Set<String> logicalNames = new LinkedHashSet<>();
            for (String name : selection.getNames()) {
                if (name != null && !name.isBlank()) {
                    logicalNames.add(logicalName(name.trim()));
                }
            }
            for (String logicalName : logicalNames) {
                Path path = imagePaths.resolve(wantGz ? logicalName + GZ : logicalName);
                if (Files.isRegularFile(path)) {
                    matches.add(path);
                }
            }
        } else {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(imagePaths.baseDir())) {
                for (Path path : stream) {
                    String name = path.getFileName().toString();
                    boolean isGz = name.toLowerCase(Locale.ROOT).endsWith(GZ);
                    if (isGz != wantGz) {
                        continue;
                    }
                    String logicalName = isGz ? name.substring(0, name.length() - GZ.length()) : name;
                    if (!extensions.contains(extensionOf(logicalName))) {
                        continue;
                    }
                    if (prefix != null && !prefix.isEmpty() && !name.startsWith(prefix)) {
                        continue;
                    }
                    if (before != null || selection.getAfter() != null) {
                        LocalDateTime captured = capturedAt(path).orElse(null);
                        if (captured == null) {
                            continue;
                        }
                        if (before != null && !captured.isBefore(before)) {
                            continue;
                        }
                        if (selection.getAfter() != null && captured.isBefore(selection.getAfter())) {
                            continue;
                        }
                    }
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    if (!selection.isIncludeEmpty() && Files.size(path) == 0) {
                        continue;
                    }
                    matches.add(path);
                }
            }
        }

        matches.sort(Comparator.comparing(p -> p.getFileName().toString()));
        if (selection.getLimit() != null && selection.getLimit() > 0 && matches.size() > selection.getLimit()) {
            return new ArrayList<>(matches.subList(0, selection.getLimit()));
        }
        return matches;
    }

    // ------------------------------------------------------------------ stats

    public ArchiveStats stats(boolean refresh) throws IOException {
        ArchiveStats current = cachedStats;
        if (!refresh && current != null
                && Duration.between(current.generatedAt(), Instant.now()).compareTo(STATS_TTL) < 0) {
            return current;
        }
        synchronized (statsLock) {
            current = cachedStats;
            if (!refresh && current != null
                    && Duration.between(current.generatedAt(), Instant.now()).compareTo(STATS_TTL) < 0) {
                return current;
            }
            ArchiveStats fresh = scanStats();
            cachedStats = fresh;
            return fresh;
        }
    }

    public void invalidateStats() {
        cachedStats = null;
    }

    private ArchiveStats scanStats() throws IOException {
        long started = System.nanoTime();
        long totalFiles = 0, totalBytes = 0;
        long fitsPlainFiles = 0, fitsPlainBytes = 0, fitsGzFiles = 0, fitsGzBytes = 0;
        long jpgFiles = 0, jpgBytes = 0, otherFiles = 0, otherBytes = 0, emptyFiles = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(imagePaths.baseDir())) {
            for (Path path : stream) {
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(path, BasicFileAttributes.class);
                } catch (IOException e) {
                    continue;
                }
                if (!attrs.isRegularFile()) {
                    continue;
                }
                String name = path.getFileName().toString();
                long size = attrs.size();
                totalFiles++;
                totalBytes += size;
                if (size == 0) {
                    emptyFiles++;
                }
                boolean gz = name.toLowerCase(Locale.ROOT).endsWith(GZ);
                String ext = extensionOf(gz ? name.substring(0, name.length() - GZ.length()) : name);
                if (defaultExtensions.contains(ext)) {
                    if (gz) {
                        fitsGzFiles++;
                        fitsGzBytes += size;
                    } else {
                        fitsPlainFiles++;
                        fitsPlainBytes += size;
                    }
                } else if (JPG_EXTENSIONS.contains(ext)) {
                    jpgFiles++;
                    jpgBytes += size;
                } else {
                    otherFiles++;
                    otherBytes += size;
                }
            }
        }

        long diskTotal = 0, diskFree = 0;
        try {
            FileStore store = Files.getFileStore(imagePaths.baseDir());
            diskTotal = store.getTotalSpace();
            diskFree = store.getUsableSpace();
        } catch (IOException e) {
            log.warn("Cannot read file store for {}: {}", imagePaths.baseDir(), e.getMessage());
        }

        return new ArchiveStats(
                Instant.now(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                imagePaths.baseDir().toString(),
                totalFiles, totalBytes,
                fitsPlainFiles, fitsPlainBytes,
                fitsGzFiles, fitsGzBytes,
                jpgFiles, jpgBytes,
                otherFiles, otherBytes,
                emptyFiles,
                diskTotal, diskFree);
    }

    // ---------------------------------------------------------------- helpers

    private static String logicalName(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            throw new IllegalArgumentException("Image file name is empty");
        }
        String name = requestedName.trim();
        return name.toLowerCase(Locale.ROOT).endsWith(GZ) ? name.substring(0, name.length() - GZ.length()) : name;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeExtensions(List<String> raw) {
        List<String> result = new ArrayList<>();
        for (String ext : raw) {
            if (ext == null) {
                continue;
            }
            String trimmed = ext.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            result.add(trimmed.startsWith(".") ? trimmed : "." + trimmed);
        }
        return Collections.unmodifiableList(result);
    }

    private boolean isTooRecent(Path path) throws IOException {
        if (minAgeMinutes == 0) {
            return false;
        }
        Instant modified = Files.getLastModifiedTime(path).toInstant();
        return modified.isAfter(Instant.now().minus(Duration.ofMinutes(minAgeMinutes)));
    }

    /** Capture time from the file name (preferred, mtimes were mass-touched once) or else the mtime. */
    private static Optional<LocalDateTime> capturedAt(Path path) {
        Matcher m = FILE_TIMESTAMP.matcher(path.getFileName().toString());
        if (m.find()) {
            try {
                return Optional.of(LocalDateTime.parse(m.group(1)));
            } catch (DateTimeParseException ignored) {
                // fall through to mtime
            }
        }
        try {
            return Optional.of(LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** GZIPOutputStream with a configurable deflate level. */
    private static final class LevelGzipOutputStream extends GZIPOutputStream {
        LevelGzipOutputStream(OutputStream out, int level) throws IOException {
            super(out, BUFFER);
            def.setLevel(level);
        }
    }
}
