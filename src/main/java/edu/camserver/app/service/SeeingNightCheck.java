package edu.camserver.app.service;

import edu.camserver.app.config.HealthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Did the seeing monitor measure anything last night? The producer stores a record every 30 s
 * whether or not anyone watches, but a wrong exposure or a lost Polaris makes every record
 * unusable; this turns that into a WARN on the health panel instead of an empty history graph.
 */
@Component
public class SeeingNightCheck {

    private static final Logger log = LoggerFactory.getLogger(SeeingNightCheck.class);
    private static final Duration RECHECK = Duration.ofMinutes(30);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US);
    private static final DateTimeFormatter TIME_ZONE = DateTimeFormatter.ofPattern("HH:mm z", Locale.US);

    /** Counts for one night; {@code measurement} is the camera setting in force when checked. */
    public record NightSummary(Instant start, Instant end, String label, long records, long validRecords,
                               Map<String, Long> statuses, Map<String, Long> exposuresUs, Map<String, Long> gains,
                               Instant checkedAt) {
    }

    private final HealthProperties.Seeing config;
    private final SeeingHistoryService history;
    private NightSummary cached;

    public SeeingNightCheck(HealthProperties properties, SeeingHistoryService history) {
        this.config = properties.getSeeing();
        this.history = history;
    }

    /** The last completed night's summary; re-read every 30 min (late replays can still arrive). */
    public synchronized Optional<NightSummary> lastNight(Instant now) {
        if (!config.isNightCheck()) return Optional.empty();
        Optional<SunPosition.Night> night = SunPosition.lastCompletedNight(config.getSiteLatitudeDeg(),
                config.getSiteLongitudeDeg(), config.getNightSunAltitudeDeg(), now);
        if (night.isEmpty()) return Optional.empty();
        if (cached != null && cached.start().equals(night.get().start())
                && cached.checkedAt().plus(RECHECK).isAfter(now)) {
            return Optional.of(cached);
        }
        try {
            SeeingHistoryService.Stats stats = history.summarize(night.get().start(), night.get().end());
            cached = new NightSummary(night.get().start(), night.get().end(), label(night.get()), stats.records(),
                    stats.valid(), stats.statuses(), stats.exposuresUs(), stats.gains(), now);
            return Optional.of(cached);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot summarize last night's seeing history: {}", e.toString());
            return Optional.ofNullable(cached);
        }
    }

    private String label(SunPosition.Night night) {
        ZoneId zone = ZoneId.of(config.getTimeZone());
        return DAY_TIME.format(night.start().atZone(zone)) + " – " + TIME_ZONE.format(night.end().atZone(zone));
    }

    /**
     * WARN with the night's counts and settings when nothing usable was measured; no reason
     * otherwise. {@code measurement} is the current camera setting, for the advice text.
     */
    public static HealthService.Evaluation evaluate(NightSummary night, Map<String, Object> measurement) {
        if (night == null) return new HealthService.Evaluation(HealthService.Status.OK, List.of());
        List<String> reasons = new ArrayList<>();
        if (night.records() == 0) {
            reasons.add("No seeing records were stored last night (" + night.label() + "): the producer was not recording.");
        } else if (night.validRecords() == 0) {
            StringBuilder text = new StringBuilder("No valid seeing measurement last night (" + night.label() + "): "
                    + String.format(Locale.US, "%,d", night.records()) + " records, none usable (" + counts(night.statuses()) + ").");
            if (!night.exposuresUs().isEmpty()) {
                text.append(" Exposure ").append(mostCommon(night.exposuresUs())).append(" µs, gain ")
                        .append(mostCommon(night.gains())).append('.');
            }
            if (measurement != null && measurement.get("exposure") != null) {
                text.append(" Current camera setting: ").append(Boolean.TRUE.equals(measurement.get("autoExposure")) ? "automatic, at most " : "manual ")
                        .append(measurement.get("exposure")).append(" µs, gain ").append(measurement.get("gain")).append('.');
            }
            text.append(" Check the measurement settings in the admin panel and whether Polaris is on the sensor.");
            reasons.add(text.toString());
        }
        return new HealthService.Evaluation(reasons.isEmpty() ? HealthService.Status.OK : HealthService.Status.WARN, reasons);
    }

    private static String counts(Map<String, Long> statuses) {
        return statuses.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> e.getKey() + " " + String.format(Locale.US, "%,d", e.getValue()))
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static String mostCommon(Map<String, Long> values) {
        return values.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("?");
    }
}
