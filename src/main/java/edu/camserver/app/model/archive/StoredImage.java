package edu.camserver.app.model.archive;

import java.nio.file.Path;

/**
 * Where an image file actually lives on disk.
 *
 * @param logicalName the name clients ask for, e.g. {@code frame.fits} (never ends in .gz)
 * @param path        the file that holds the bytes: either {@code frame.fits} or {@code frame.fits.gz}
 * @param gzipped     whether {@code path} is the gzip-compressed form
 * @param size        size in bytes of {@code path}
 */
public record StoredImage(String logicalName, Path path, boolean gzipped, long size) {
}
