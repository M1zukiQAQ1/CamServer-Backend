package edu.camserver.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.nio.file.Path;

@Component
public class ImagePaths {

    private final Path baseDir;

    public ImagePaths(@Value("${app.images.base-dir}") String baseDir) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    public Path baseDir() {
        return baseDir;
    }

    public File fileFor(String fileName) {
        return resolve(fileName).toFile();
    }

    /**
     * The JPEG that belongs to an {@code ImgPath} value, when it is on disk and not empty.
     * {@code ImgPath} normally has no extension; older rows may carry one.
     */
    public Optional<Path> jpegOf(String imgPath) {
        if (imgPath == null || imgPath.isBlank()) {
            return Optional.empty();
        }
        String name = Path.of(imgPath).getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        List<String> candidates = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                ? List.of(name) : List.of(name + ".jpg", name + ".jpeg");
        for (String candidate : candidates) {
            try {
                Path path = resolve(candidate);
                if (Files.isRegularFile(path) && Files.size(path) > 0) {
                    return Optional.of(path);
                }
            } catch (IllegalArgumentException | IOException ignored) {
                // not a usable file name
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a bare file name inside the image directory. Names that would escape the
     * directory (absolute paths, "..", nested paths) are rejected.
     */
    public Path resolve(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Image file name is empty");
        }
        Path resolved = baseDir.resolve(fileName).normalize();
        if (resolved.equals(baseDir) || !resolved.getParent().equals(baseDir)) {
            throw new IllegalArgumentException("Invalid image file name: " + fileName);
        }
        return resolved;
    }
}
