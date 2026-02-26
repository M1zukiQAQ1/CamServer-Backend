package edu.camserver.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

@Component
public class ImagePaths {

    private final Path baseDir;

    public ImagePaths(@Value("${app.images.base-dir}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    public File fileFor(String fileName) {
        return baseDir.resolve(fileName).normalize().toFile();
    }

    public Path resolve(String fileName) {
        return baseDir.resolve(fileName); // Should
    }
}
