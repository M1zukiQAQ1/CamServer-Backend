package edu.camserver.app.sky;

import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.Image;
import edu.camserver.app.service.ImageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreakServiceTest {
    @TempDir Path dir;

    private StreakService serviceWith(int exposureUs, Boolean day) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/lost/frame-57316.jpg")) {
            Files.copy(in, dir.resolve("frame.jpg"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Image image = new Image("cam", "site", Instant.now(), 16, 60, exposureUs, "/mnt/CamData/images/frame", 20f, 40f, "UTC", false);
        image.setIsDayTime(day);
        ImageService images = mock(ImageService.class);
        when(images.findById(7L)).thenReturn(image);
        return new StreakService(images, new ImagePaths(dir.toString()));
    }

    @Test
    void looksAtALongExposure() throws IOException {
        StreakService.StreakView view = serviceWith(13_000_000, false).view(7);
        assertThat(view.status()).isEqualTo(StreakService.Status.OK);
        assertThat(view.width()).isEqualTo(2500);
        assertThat(view.streaks()).isEmpty();
    }

    @Test
    void skipsDayAndTwilightExposuresEvenWhenTheCameraCallsThemNight() throws IOException {
        assertThat(serviceWith(100, false).view(7).status()).isEqualTo(StreakService.Status.DAYTIME);
        assertThat(serviceWith(1_154_138, false).view(7).status()).isEqualTo(StreakService.Status.DAYTIME);   // twilight
        assertThat(serviceWith(13_000_000, true).view(7).status()).isEqualTo(StreakService.Status.DAYTIME);
    }

    @Test
    void reportsAMissingPicture() throws IOException {
        StreakService service = serviceWith(13_000_000, null);
        Files.delete(dir.resolve("frame.jpg"));
        assertThat(service.view(7).status()).isEqualTo(StreakService.Status.NO_IMAGE);
    }
}
