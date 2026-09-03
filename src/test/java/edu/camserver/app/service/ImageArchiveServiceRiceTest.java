package edu.camserver.app.service;

import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.archive.ArchiveFileInfo;
import edu.camserver.app.model.archive.ArchiveFileResult;
import edu.camserver.app.model.archive.ArchiveJob;
import edu.camserver.app.model.archive.ArchiveSelection;
import edu.camserver.app.model.archive.ArchiveStats;
import edu.camserver.app.model.archive.StoredImage;
import edu.camserver.app.service.fits.RiceArchiver;
import edu.camserver.app.service.fits.ShiftedFits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end archive behaviour with the Rice format. Needs fpack and imcopy; skipped otherwise. */
class ImageArchiveServiceRiceTest {

    @TempDir
    Path root;
    Path images;
    ImageArchiveService service;

    @BeforeEach
    void setUp() throws IOException {
        images = Files.createDirectories(root.resolve("images"));
        service = newService("rice");
        RiceArchiver.Availability availability = service.riceAvailability();
        Assumptions.assumeTrue(availability.available(), "fpack/imcopy not installed: " + availability.detail());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private ImageArchiveService newService(String format) {
        return new ImageArchiveService(new ImagePaths(images.toString()), 6, 2, 0, ".fits,.fit,.fts",
                root.resolve("archive-tmp").toString(), format, "fpack", "imcopy", 2, 120);
    }

    private static byte[] frame(long seed) {
        Random random = new Random(seed);
        int width = 240, height = 80, planes = 3;
        byte[] header = headerBytes(width, height, planes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        int count = width * height * planes;
        for (int i = 0; i < count; i++) {
            int unsigned = ((1200 + random.nextInt(300)) & 0x3fff) << 2;
            short stored = (short) (unsigned - 32768);
            out.write(stored >> 8);
            out.write(stored);
        }
        long dataBytes = (long) count * 2;
        long padding = ((dataBytes + 2879) / 2880) * 2880 - dataBytes;
        for (long i = 0; i < padding; i++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] headerBytes(int width, int height, int planes) {
        StringBuilder sb = new StringBuilder();
        for (String card : new String[] {
                "SIMPLE  =                    T / conforms to FITS standard",
                "BITPIX  =                   16 / array data type",
                "NAXIS   =                    3 / number of array dimensions",
                "NAXIS1  = " + String.format("%20d", width),
                "NAXIS2  = " + String.format("%20d", height),
                "NAXIS3  = " + String.format("%20d", planes),
                "EXTEND  =                    T",
                "BSCALE  =                    1",
                "BZERO   =                32768",
                "END"}) {
            sb.append(String.format("%-80s", card));
        }
        while (sb.length() % 2880 != 0) {
            sb.append(' ');
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static ShiftedFits.Scan scanOf(byte[] bytes) throws IOException {
        return ShiftedFits.scanStream(new ByteArrayInputStream(bytes));
    }

    @Test
    void compressLocateServeAndDecompress() throws IOException {
        String name = "QHY5III678C-test_2026-01-05T01:02:03.456.fits";
        byte[] original = frame(1);
        Files.write(images.resolve(name), original);

        ArchiveFileResult result = service.compress(name, false);
        assertTrue(result.changed(), result.message());
        assertTrue(result.message().contains("2-bit shift"), result.message());
        assertTrue(result.bytesAfter() < result.bytesBefore());
        assertFalse(Files.exists(images.resolve(name)));
        assertTrue(Files.exists(images.resolve(name + ".fz")));

        StoredImage stored = service.locate(name).orElseThrow();
        assertEquals(StoredImage.Format.RICE, stored.format());
        assertTrue(stored.compressed());
        assertFalse(stored.gzipped());
        assertEquals(name, stored.logicalName());
        assertEquals(StoredImage.Format.RICE, service.locate(name + ".fz").orElseThrow().format());
        assertEquals(StoredImage.Format.RICE, service.locate(name + ".gz").orElseThrow().format(),
                "a .gz request still finds the frame in its stored form");

        ArchiveFileInfo info = service.describe(name);
        assertEquals("rice", info.format());
        assertEquals(name + ".fz", info.storedAs());
        assertFalse(info.gzipped());

        try (InputStream in = service.openDecompressed(stored)) {
            assertTrue(scanOf(original).matches(scanOf(in.readAllBytes())), "served plain frame is pixel-identical");
        }

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        service.writeGzipped(stored, gz);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz.toByteArray()))) {
            assertTrue(scanOf(original).matches(scanOf(in.readAllBytes())), "on-the-fly gzip is pixel-identical");
        }

        Path materialized = service.materialize(stored);
        assertTrue(scanOf(original).matches(ShiftedFits.scan(materialized, 0)));
        assertEquals(materialized, service.materialize(stored), "materialised copy is reused");

        assertEquals("already compressed", service.compress(name, false).message());

        ArchiveFileResult back = service.decompress(name, false);
        assertTrue(back.changed());
        assertFalse(Files.exists(images.resolve(name + ".fz")));
        assertTrue(scanOf(original).matches(ShiftedFits.scan(images.resolve(name), 0)));
        assertEquals("already plain", service.decompress(name, false).message());
    }

    @Test
    void gzipArchivesAreConvertedByCompressJobs() throws Exception {
        byte[] plainFrame = frame(2);
        byte[] gzFrame = frame(3);
        String plainName = "cam_2026-02-01T00:00:00.000.fits";
        String gzName = "cam_2026-02-02T00:00:00.000.fits";
        Files.write(images.resolve(plainName), plainFrame);
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(images.resolve(gzName + ".gz")))) {
            out.write(gzFrame);
        }
        Files.write(images.resolve("empty_2026-02-03T00:00:00.000.fits"), new byte[0]);
        Files.write(images.resolve("note.txt"), "not a frame".getBytes(StandardCharsets.UTF_8));

        ArchiveSelection selection = new ArchiveSelection();
        selection.setAll(true);
        ArchiveJob job = service.startJob(ArchiveJob.Type.COMPRESS, selection);
        waitFor(job);

        assertEquals(ArchiveJob.State.COMPLETED, job.getState(), job.getMessage());
        assertEquals(2, job.getCandidates(), "the empty file and the text file are not candidates");
        assertEquals(2, job.getSucceeded());
        assertEquals(0, job.getFailed(), String.join("; ", job.getErrors()));
        assertTrue(Files.exists(images.resolve(plainName + ".fz")));
        assertTrue(Files.exists(images.resolve(gzName + ".fz")));
        assertFalse(Files.exists(images.resolve(plainName)));
        assertFalse(Files.exists(images.resolve(gzName + ".gz")));

        try (InputStream in = service.openDecompressed(service.locate(gzName).orElseThrow())) {
            assertTrue(scanOf(gzFrame).matches(scanOf(in.readAllBytes())));
        }

        ArchiveStats stats = service.stats(true);
        assertEquals(2, stats.fitsRiceFiles());
        assertEquals(0, stats.fitsGzipFiles());
        assertEquals(1, stats.fitsPlainFiles(), "the empty .fits still counts as plain");
        assertEquals(1, stats.emptyFiles());

        // a decompress job takes both back to plain
        ArchiveJob undo = service.startJob(ArchiveJob.Type.DECOMPRESS, selection);
        waitFor(undo);
        assertEquals(2, undo.getSucceeded(), undo.getMessage());
        assertTrue(scanOf(gzFrame).matches(ShiftedFits.scan(images.resolve(gzName), 0)));
        assertTrue(scanOf(plainFrame).matches(ShiftedFits.scan(images.resolve(plainName), 0)));
    }

    @Test
    void conflictingFormsAreLeftAlone() throws IOException {
        String name = "dup_2026-03-01T00:00:00.000.fits";
        Files.write(images.resolve(name), frame(4));
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(images.resolve(name + ".gz")))) {
            out.write(frame(4));
        }
        ArchiveFileResult result = service.compress(name, false);
        assertFalse(result.changed());
        assertTrue(result.message().contains("both plain and .gz exist"), result.message());
        assertTrue(Files.exists(images.resolve(name)));
        assertTrue(Files.exists(images.resolve(name + ".gz")));
    }

    @Test
    void dryRunTouchesNothing() throws IOException {
        String name = "dry_2026-03-01T00:00:00.000.fits";
        Files.write(images.resolve(name), frame(5));
        ArchiveFileResult result = service.compress(name, true);
        assertTrue(result.changed());
        assertEquals("dry run", result.message());
        assertTrue(Files.exists(images.resolve(name)));
        assertFalse(Files.exists(images.resolve(name + ".fz")));
    }

    @Test
    void gzipFormatStillWorks() throws IOException {
        service.shutdown();
        service = newService("gzip");
        String name = "old_2026-03-01T00:00:00.000.fits";
        byte[] original = frame(6);
        Files.write(images.resolve(name), original);
        ArchiveFileResult result = service.compress(name, false);
        assertTrue(result.changed());
        assertTrue(Files.exists(images.resolve(name + ".gz")));
        StoredImage stored = service.locate(name).orElseThrow();
        assertEquals(StoredImage.Format.GZIP, stored.format());
        assertTrue(stored.gzipped());
        try (InputStream in = service.openDecompressed(stored)) {
            assertTrue(scanOf(original).matches(scanOf(in.readAllBytes())));
        }
        assertEquals("gzip", service.config().get("format"));
    }

    private static void waitFor(ArchiveJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (job.isActive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(job.isActive(), "job did not finish: " + job.getState());
    }

    @SuppressWarnings("unused")
    private static List<String> names(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> names.add(p.getFileName().toString()));
        }
        return names;
    }
}
