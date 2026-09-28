package edu.camserver.app.repository;

import edu.camserver.app.model.Image;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ImageRepository extends JpaRepository<Image, Long>, QuerydslPredicateExecutor<Image> {

    /** Every distinct {@code TimeZone} value on the image rows; contains {@code null} when rows without one exist. */
    @Query("select distinct i.timeZone from Image i")
    List<String> findDistinctTimeZones();

    /** The earliest capture time in the table as UTC wall-clock time, or {@code null} when it is empty. */
    @Query("select min(i.timestampUtc) from Image i")
    LocalDateTime findEarliestTimestampUtc();

    /**
     * The newest frames of one camera, newest first; pass a one-element page for the latest.
     * {@code CamId} is a fixed-width column, but SQL Server ignores trailing spaces when comparing.
     */
    @Query("select i from Image i where i.cameraId = :cameraId order by i.timestampUtc desc, i.imgId desc")
    List<Image> findNewestByCamera(@Param("cameraId") String cameraId, Pageable pageable);

    /** How many frames of one camera were captured at or after {@code sinceUtc} (UTC wall-clock time). */
    @Query("select count(i) from Image i where i.cameraId = :cameraId and i.timestampUtc >= :sinceUtc")
    long countByCameraSince(@Param("cameraId") String cameraId, @Param("sinceUtc") LocalDateTime sinceUtc);
}
