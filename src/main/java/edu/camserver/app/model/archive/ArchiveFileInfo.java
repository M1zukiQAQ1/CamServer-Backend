package edu.camserver.app.model.archive;

import java.time.Instant;

public record ArchiveFileInfo(
        String fileName,
        boolean exists,
        boolean gzipped,
        String storedAs,
        long sizeBytes,
        Instant modifiedAt) {
}
