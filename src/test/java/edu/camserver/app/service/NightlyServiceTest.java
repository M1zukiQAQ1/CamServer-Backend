package edu.camserver.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.controller.NightlyController;
import edu.camserver.app.model.Camera;
import edu.camserver.app.model.Image;
import edu.camserver.app.repository.CameraRepository;
import edu.camserver.app.repository.ImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NightlyServiceTest {
    private static final String CAMERA = "QHY5III678M-test";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 8);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @TempDir Path tmp;
    private Path imageDir;
    private Path nightlyDir;
    private Camera camera;
    private ImageRepository images;
    private CameraRepository cameras;

    @BeforeEach
    void setUp() throws IOException {
        imageDir = Files.createDirectory(tmp.resolve("images"));
        nightlyDir = tmp.resolve("nightly");
        camera = new Camera();
        camera.setCameraId(CAMERA + "   ");            // the column is fixed-width
        camera.setSiteName("UCSB Broida Roof -M");
        camera.setTimeZone("America/Los_Angeles");
        camera.setLatitude(34.4133);
        camera.setLongitude(-119.861);
        images = mock(ImageRepository.class);
        cameras = mock(CameraRepository.class);
        when(cameras.findAll()).thenReturn(List.of(camera));
    }

    private NightlyService service(String ffmpeg) {
        return new NightlyService(images, cameras, new ImagePaths(imageDir.toString()), MAPPER, true, nightlyDir.toString(),
                -6, 12, 960, 540, ffmpeg, 12, 1080, Duration.ofSeconds(20), 10);
    }

    /** One frame every five minutes through the night; frame i is a flat grey of level 10 + i, with a hole of `gap` frames. */
    private List<Image> frames(SunPosition.Night night, int gapFrom, int gapTo) throws IOException {
        List<Image> rows = new ArrayList<>();
        int i = 0;
        for (Instant at = night.start().plusSeconds(60); at.isBefore(night.end()); at = at.plusSeconds(300), i++) {
            if (i >= gapFrom && i < gapTo) {
                continue;
            }
            String name = CAMERA + "_" + LocalDateTime.ofInstant(at, ZoneOffset.UTC).toString().replace("Z", "");
            BufferedImage picture = new BufferedImage(200, 160, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = picture.createGraphics();
            g.setColor(new Color(10 + i, 10 + i, 10 + i));
            g.fillRect(0, 0, 200, 160);
            g.dispose();
            ImageIO.write(picture, "jpg", imageDir.resolve(name + ".jpg").toFile());
            Image row = new Image(CAMERA, "site", at, 16, 60, 13_000_000, "/mnt/CamData/images/" + name, 20f, 40f,
                    "America/Los_Angeles", false);
            row.setImgId(1000L + i);
            rows.add(row);
        }
        when(images.findByCameraBetween(eq(CAMERA), any(), any())).thenReturn(rows);
        return rows;
    }

    @Test
    void aNovemberNightAtBroidaRunsFromDuskToDawn() {
        SunPosition.Night night = service("ffmpeg").nightOf(camera, DATE).orElseThrow();
        // Civil dusk on 8 Nov is about 17:26 PST, civil dawn on the 9th about 06:02 PST.
        assertThat(night.start()).isBetween(Instant.parse("2026-11-09T01:15:00Z"), Instant.parse("2026-11-09T01:40:00Z"));
        assertThat(night.end()).isBetween(Instant.parse("2026-11-09T13:50:00Z"), Instant.parse("2026-11-09T14:15:00Z"));
    }

    @Test
    void buildsAKeogramWithTimeRunningLeftToRightAndGapsLeftBlack() throws IOException {
        NightlyService service = service(tmp.resolve("no-such-ffmpeg").toString());
        SunPosition.Night span = service.nightOf(camera, DATE).orElseThrow();
        List<Image> rows = frames(span, 60, 90);   // 2.5 hours without uploads

        NightlyService.Night night = service.build(CAMERA, DATE).orElseThrow();
        assertThat(night.cameraId()).isEqualTo(CAMERA);
        assertThat(night.frames()).hasSize(rows.size());
        assertThat(night.frames().get(0).imgId()).isEqualTo(1000L);
        assertThat(night.timelapse()).isNull();
        assertThat(night.timelapseNote()).contains("ffmpeg is not installed");

        BufferedImage keogram = ImageIO.read(service.file(CAMERA, DATE.toString(), NightlyService.KEOGRAM).orElseThrow().toFile());
        assertThat(keogram.getWidth()).isEqualTo(960);
        assertThat(keogram.getHeight()).isEqualTo(540);
        double span_ms = Duration.between(span.start(), span.end()).toMillis();
        // Brightness rises with time: frame i is level 10 + i.
        for (int i : new int[] {5, 40, 100, 140}) {
            Instant at = span.start().plusSeconds(60 + 300L * i + 150);
            int x = (int) (Duration.between(span.start(), at).toMillis() / span_ms * 960);
            assertThat(keogram.getRGB(x, 270) & 255).isBetween(10 + i - 4, 10 + i + 4);
        }
        // Inside the gap, beyond three frame intervals after frame 59: black.
        Instant inGap = span.start().plusSeconds(60 + 300L * 75);
        int gapX = (int) (Duration.between(span.start(), inGap).toMillis() / span_ms * 960);
        assertThat(keogram.getRGB(gapX, 270) & 255).isLessThan(6);

        assertThat(service.list(null, 30)).hasSize(1);
        assertThat(service.list(CAMERA, 30).get(0).date()).isEqualTo(DATE);
        assertThat(service.list("other", 30)).isEmpty();
        assertThat(Files.list(nightlyDir.resolve(CAMERA).resolve(DATE.toString())).map(p -> p.getFileName().toString()))
                .containsExactlyInAnyOrder("night.json", "keogram.jpg");
    }

    @Test
    void handsEveryFrameToFfmpegInOrderAndKeepsTheVideo() throws IOException {
        // Stands in for ffmpeg: copies the frame list it was given next to the output and writes a "video".
        Path fake = tmp.resolve("fake-ffmpeg.sh");
        Files.writeString(fake, """
                #!/bin/sh
                for a in "$@"; do prev="$last"; last="$a"; case "$seen" in i) [ -z "$list" ] && list="$a"; labels="$a";; esac; seen=""; [ "$a" = "-i" ] && seen=i; done
                cp "$list" "$(dirname "$last")/frames-seen.txt"
                cp "$labels" "$(dirname "$last")/labels-seen.txt"
                cp "$(dirname "$last")/labels/000003.png" "$(dirname "$last")/label-seen.png"
                echo "$@" > "$(dirname "$last")/args-seen.txt"
                printf 'video' > "$last"
                """);
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        NightlyService service = service(fake.toString());
        List<Image> rows = frames(service.nightOf(camera, DATE).orElseThrow(), 0, 0);

        NightlyService.Night night = service.build(CAMERA, DATE).orElseThrow();
        assertThat(night.timelapse()).isEqualTo("timelapse.mp4");
        assertThat(night.timelapseNote()).isNull();
        Path dir = nightlyDir.resolve(CAMERA).resolve(DATE.toString());
        assertThat(Files.readString(dir.resolve("timelapse.mp4"))).isEqualTo("video");
        List<String> listed = Files.readAllLines(dir.resolve("frames-seen.txt")).stream().filter(l -> l.startsWith("file ")).toList();
        assertThat(listed).hasSize(rows.size());
        assertThat(listed.get(0)).contains(Path.of(rows.get(0).getImgPath()).getFileName() + ".jpg");
        assertThat(listed.get(listed.size() - 1)).contains(Path.of(rows.get(rows.size() - 1).getImgPath()).getFileName() + ".jpg");
        assertThat(Files.readString(dir.resolve("args-seen.txt"))).contains("-f concat -safe 0").contains("libx264").contains("scale=1080:-2");
        // Every frame has its label, laid over the bottom left corner.
        assertThat(night.timelapseLabelled()).isTrue();
        assertThat(Files.readAllLines(dir.resolve("labels-seen.txt")).stream().filter(l -> l.startsWith("file "))).hasSize(rows.size());
        assertThat(Files.readString(dir.resolve("args-seen.txt"))).contains("overlay=x=12:y=main_h-overlay_h-12");
        BufferedImage label = ImageIO.read(dir.resolve("label-seen.png").toFile());
        assertThat(label.getWidth()).isLessThan(1080 / 2);
        int white = 0;
        for (int y = 0; y < label.getHeight(); y++) {
            for (int x = 0; x < label.getWidth(); x++) {
                white += label.getRGB(x, y) == 0xFFFFFFFF ? 1 : 0;
            }
        }
        assertThat(white).isGreaterThan(200);
        assertThat(label.getRGB(0, 0) >>> 24).isZero();   // transparent around the text
        // Work files are gone.
        assertThat(Files.exists(dir.resolve("frames.txt"))).isFalse();
        assertThat(Files.exists(dir.resolve("labels.txt"))).isFalse();
        assertThat(Files.exists(dir.resolve("labels"))).isFalse();
        assertThat(Files.exists(dir.resolve("ffmpeg.log"))).isFalse();
    }

    @Test
    void theLabelNamesTheSiteThenTheUtcTimeThenTheCoordinates() {
        assertThat(NightlyService.labelLines(camera)).containsExactly("UCSB Broida Roof -M", null, "34.4133\u00b0 N  119.8610\u00b0 W");
        camera.setLatitude(null);       // no coordinates on record, and none in the site table
        camera.setSiteName("  ");
        assertThat(NightlyService.labelLines(camera)).containsExactly(CAMERA, null);
    }

    @Test
    void aNightWhoseVideoHasNoLabelIsBuiltAgainOncePerRun() throws IOException {
        Instant now = DATE.plusDays(1).atTime(12, 0).atZone(java.time.ZoneId.of("America/Los_Angeles")).toInstant();
        NightlyService before = service("/nonexistent/ffmpeg");
        SunPosition.Night span = before.nightOf(camera, DATE).orElseThrow();
        List<Image> rows = frames(span, 0, 0);
        // Only the night of DATE has frames.
        when(images.findByCameraBetween(eq(CAMERA), any(), any())).thenAnswer(call ->
                call.<LocalDateTime>getArgument(1).toInstant(ZoneOffset.UTC).equals(span.start()) ? rows : List.of());
        assertThat(before.catchUp(now)).isEqualTo(1);
        Path summary = nightlyDir.resolve(CAMERA).resolve(DATE.toString()).resolve("night.json");
        assertThat(MAPPER.readTree(summary.toFile()).path("timelapseLabelled").asBoolean()).isFalse();

        // After a restart, with ffmpeg now there: the night is built again and gets its labelled video.
        Path fake = tmp.resolve("ffmpeg-ok.sh");
        Files.writeString(fake, "#!/bin/sh\nfor a in \"$@\"; do last=\"$a\"; done\nprintf 'video' > \"$last\"\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        NightlyService after = service(fake.toString());
        assertThat(after.catchUp(now)).isEqualTo(1);
        assertThat(after.list(CAMERA, 5).get(0).timelapseLabelled()).isTrue();
        assertThat(after.catchUp(now)).isZero();
        assertThat(service(fake.toString()).catchUp(now)).isZero();   // and a later restart leaves it alone
    }

    @Test
    void aVideoAskedForIsMadeInTheBackgroundAndThenReady() throws Exception {
        Path fake = tmp.resolve("ffmpeg-ok.sh");
        Files.writeString(fake, "#!/bin/sh\nfor a in \"$@\"; do last=\"$a\"; done\nprintf 'video' > \"$last\"\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        NightlyService service = service(fake.toString());
        SunPosition.Night span = service.nightOf(camera, DATE).orElseThrow();
        List<Image> rows = frames(span, 0, 0);
        when(images.findByCameraBetween(eq(CAMERA), any(), any())).thenAnswer(call ->
                call.<LocalDateTime>getArgument(1).toInstant(ZoneOffset.UTC).equals(span.start()) ? rows : List.of());
        Instant now = span.end().plusSeconds(3600);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new NightlyController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER)).build();

        assertThat(service.status(CAMERA, DATE).state()).isEqualTo("none");
        assertThat(service.request(CAMERA, DATE, span.end().minusSeconds(60)).message()).contains("not over yet");
        assertThat(service.request("nobody", DATE, now).message()).contains("Unknown camera");
        assertThat(service.request(CAMERA, DATE, now).state()).isIn("queued", "building");
        NightlyService.VideoStatus status = service.status(CAMERA, DATE);
        for (int i = 0; i < 200 && !status.state().equals("ready"); i++) {
            Thread.sleep(50);
            status = service.status(CAMERA, DATE);
        }
        assertThat(status.state()).isEqualTo("ready");
        assertThat(status.night().timelapse()).isEqualTo("timelapse.mp4");
        assertThat(service.request(CAMERA, DATE, now).state()).isEqualTo("ready");   // asking again builds nothing
        org.mockito.Mockito.verify(images, org.mockito.Mockito.times(1)).findByCameraBetween(eq(CAMERA), any(), any());

        // A night without frames: said so, and not tried again straight away.
        LocalDate empty = DATE.minusDays(3);
        Instant later = Instant.now();
        service.request(CAMERA, empty, later);
        status = service.status(CAMERA, empty);
        for (int i = 0; i < 200 && !status.state().equals("unavailable"); i++) {
            Thread.sleep(50);
            status = service.status(CAMERA, empty);
        }
        assertThat(status.message()).contains("fewer than 12 frames");
        assertThat(service.request(CAMERA, empty, later).state()).isEqualTo("unavailable");
        org.mockito.Mockito.verify(images, org.mockito.Mockito.times(2)).findByCameraBetween(eq(CAMERA), any(), any());

        mvc.perform(get("/api/nightly/" + CAMERA + "/" + DATE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ready")).andExpect(jsonPath("$.night.timelapseLabelled").value(true));
        mvc.perform(post("/api/nightly/" + CAMERA + "/" + DATE)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ready"));
        mvc.perform(post("/api/nightly/" + CAMERA + "/soon")).andExpect(status().isBadRequest());
    }

    @Test
    void anOlderNightOnDiskWithoutALabelIsBuiltAgainToo() throws IOException {
        LocalDate old = DATE.minusDays(40);   // the catch-up window of the test is 10 days
        NightlyService before = service("/nonexistent/ffmpeg");
        SunPosition.Night span = before.nightOf(camera, old).orElseThrow();
        List<Image> rows = frames(span, 0, 0);
        when(images.findByCameraBetween(eq(CAMERA), any(), any())).thenAnswer(call ->
                call.<LocalDateTime>getArgument(1).toInstant(ZoneOffset.UTC).equals(span.start()) ? rows : List.of());
        before.build(CAMERA, old).orElseThrow();

        Path fake = tmp.resolve("ffmpeg-ok.sh");
        Files.writeString(fake, "#!/bin/sh\nfor a in \"$@\"; do last=\"$a\"; done\nprintf 'video' > \"$last\"\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        NightlyService after = service(fake.toString());
        Instant now = DATE.plusDays(1).atTime(12, 0).atZone(java.time.ZoneId.of("America/Los_Angeles")).toInstant();
        assertThat(after.catchUp(now)).isEqualTo(1);
        assertThat(after.list(CAMERA, 5).get(0).timelapseLabelled()).isTrue();
        assertThat(after.catchUp(now)).isZero();
    }

    @Test
    void catchesUpOnEveryPastNightThatHasFramesAndDoesNotRepeatItself() throws IOException {
        NightlyService service = service("/nonexistent/ffmpeg");
        // The camera uploaded on two of the last ten nights only.
        List<LocalDate> active = List.of(DATE, DATE.minusDays(4));
        when(images.findByCameraBetween(eq(CAMERA), any(), any())).thenAnswer(call -> {
            Instant start = call.<LocalDateTime>getArgument(1).toInstant(ZoneOffset.UTC);
            boolean hasFrames = active.stream().anyMatch(d -> service.nightOf(camera, d).orElseThrow().start().equals(start));
            List<Image> rows = new ArrayList<>();
            for (int i = 0; hasFrames && i < 20; i++) {
                Instant at = start.plusSeconds(600L * (i + 1));
                String name = CAMERA + "_" + at.getEpochSecond();
                ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_BYTE_GRAY), "jpg", imageDir.resolve(name + ".jpg").toFile());
                Image row = new Image(CAMERA, "site", at, 16, 60, 13_000_000, "/mnt/CamData/images/" + name, 20f, 40f,
                        "America/Los_Angeles", false);
                row.setImgId(at.getEpochSecond());
                rows.add(row);
            }
            return rows;
        });
        // Noon after the night of DATE: that night is over, the next has not begun.
        Instant now = DATE.plusDays(1).atTime(12, 0).atZone(java.time.ZoneId.of("America/Los_Angeles")).toInstant();

        assertThat(service.catchUp(now)).isEqualTo(2);
        assertThat(service.list(null, 30)).extracting(NightlyService.Night::date).containsExactly(DATE, DATE.minusDays(4));
        assertThat(service.catchUp(now)).isZero();
        // Each of the ten completed nights is looked at once, none twice.
        org.mockito.Mockito.verify(images, org.mockito.Mockito.times(10)).findByCameraBetween(eq(CAMERA), any(), any());
    }

    @Test
    void tooFewFramesBuildNothing() throws IOException {
        NightlyService service = service("ffmpeg");
        SunPosition.Night span = service.nightOf(camera, DATE).orElseThrow();
        frames(span, 5, 10_000);   // five frames only
        assertThat(service.build(CAMERA, DATE)).isEmpty();
        assertThat(service.build("unknown-camera", DATE)).isEmpty();
        assertThat(service.list(null, 30)).isEmpty();
    }

    @Test
    void servesFilesWithByteRangesAndNothingElse() throws Exception {
        NightlyService service = service(tmp.resolve("no-such-ffmpeg").toString());
        frames(service.nightOf(camera, DATE).orElseThrow(), 0, 0);
        service.build(CAMERA, DATE).orElseThrow();
        Path video = nightlyDir.resolve(CAMERA).resolve(DATE.toString()).resolve("timelapse.mp4");
        Files.writeString(video, "0123456789");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new NightlyController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER), new ResourceHttpMessageConverter(),
                        new ResourceRegionHttpMessageConverter())
                .build();
        String base = "/api/nightly/" + CAMERA + "/" + DATE + "/";

        mvc.perform(get("/api/nightly")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value("2026-11-08")).andExpect(jsonPath("$[0].keogram").value("keogram.jpg"));
        mvc.perform(get(base + "keogram.jpg")).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        mvc.perform(get(base + "timelapse.mp4")).andExpect(status().isOk()).andExpect(content().string("0123456789"));
        mvc.perform(get(base + "timelapse.mp4").header("Range", "bytes=2-5")).andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10")).andExpect(content().string("2345"));
        mvc.perform(get(base + "night.json")).andExpect(status().isNotFound());
        mvc.perform(get("/api/nightly/" + CAMERA + "/not-a-date/keogram.jpg")).andExpect(status().isNotFound());
        mvc.perform(get("/api/nightly/../" + DATE + "/keogram.jpg")).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/admin/nightly/" + CAMERA + "/2026-11-08")).andExpect(status().isOk())
                .andExpect(jsonPath("$.frames.length()").value(org.hamcrest.Matchers.greaterThan(100)));
        mvc.perform(post("/api/admin/nightly/" + CAMERA + "/yesterday")).andExpect(status().isBadRequest());
    }
}
