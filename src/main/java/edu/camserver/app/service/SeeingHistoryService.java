package edu.camserver.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Consumer;

/** Append-only, UTC daily journals. No automatic deletion; keep this directory on persistent storage. */
@Service
public class SeeingHistoryService {
    private static final Logger log = LoggerFactory.getLogger(SeeingHistoryService.class);
    private static final List<String> METRICS = List.of("jitterRmsPx", "jitterArcsec", "seeingArcsec", "fluxVariationPercent",
            "driftPxPerMinute", "fwhmPx", "flux", "backgroundRms");
    private static final List<String> COLUMNS = List.of("timestamp", "status", "sessionId", "segment",
            "jitterRmsPx", "jitterArcsec", "seeingArcsec", "fluxVariationPercent", "altitudeDeg", "samples", "exposureUs", "gain",
            "target", "simulated", "calibration", "driftPxPerMinute", "fwhmPx", "flux", "backgroundRms",
            "durationSeconds", "blockers", "resetReason");
    private static final Set<String> READY = Set.of("relative", "estimated", "below_noise_floor");
    private final ObjectMapper mapper;
    private final Path directory;
    private final Map<LocalDate, Set<String>> known = new LinkedHashMap<>();

    public SeeingHistoryService(ObjectMapper mapper,
            @Value("${app.live.history-dir:data/seeing-history}") String directory) {
        this.mapper = mapper;
        this.directory = Path.of(directory);
    }

    /** The producer replays its bounded history, covering short network/server outages. */
    public synchronized boolean record(Object value) {
        JsonNode seeing = mapper.valueToTree(value);
        if (seeing == null || !seeing.path("history").isArray()) return true;
        try {
            Files.createDirectories(directory);
            Map<LocalDate, List<ObjectNode>> batches = new TreeMap<>();
            double now = Instant.now().toEpochMilli() / 1000.0;
            for (JsonNode input : seeing.path("history")) {
                if (batches.values().stream().mapToInt(List::size).sum() >= 120) break;
                if (!input.isObject() || !input.path("timestamp").isNumber()) continue;
                double ts = input.path("timestamp").asDouble();
                if (!Double.isFinite(ts) || ts < 1577836800 || ts > now + 300) continue;
                ObjectNode point = mapper.createObjectNode();
                for (String key : COLUMNS) {
                    JsonNode item = input.get(key);
                    if (item == null && List.of("sessionId", "target", "simulated", "calibration").contains(key)) item = seeing.get(key);
                    if (item != null) point.set(key, item);
                }
                if (!point.path("status").isTextual() || point.path("status").asText().length() > 40
                        || point.path("sessionId").asText().length() > 80 || point.toString().length() > 4096) continue;
                LocalDate day = day(ts);
                Set<String> ids = known.get(day);
                if (ids == null) {
                    repairTail(file(day));
                    ids = new HashSet<>();
                    Set<String> loaded = ids;
                    read(day, p -> loaded.add(id(p)));
                    known.put(day, ids);
                }
                List<ObjectNode> batch = batches.computeIfAbsent(day, ignored -> new ArrayList<>());
                if (!ids.contains(id(point)) && batch.stream().noneMatch(p -> id(p).equals(id(point)))) batch.add(point);
            }
            for (var batch : batches.entrySet()) {
                if (batch.getValue().isEmpty()) continue;
                StringBuilder lines = new StringBuilder();
                for (ObjectNode point : batch.getValue()) lines.append(mapper.writeValueAsString(point)).append('\n');
                try (FileChannel out = FileChannel.open(file(batch.getKey()), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ByteBuffer bytes = StandardCharsets.UTF_8.encode(lines.toString());
                    while (bytes.hasRemaining()) out.write(bytes);
                    out.force(true);
                }
                batch.getValue().forEach(p -> known.get(batch.getKey()).add(id(p)));
            }
            while (known.size() > 8) known.remove(known.keySet().iterator().next());
            return true;
        } catch (IOException e) {
            // Reload the journal after a partial write; never acknowledge unwritten points.
            known.clear();
            log.error("Cannot persist seeing history in {}", directory, e);
            return false;
        }
    }

    public Map<String, Object> query(Instant start, Instant end, int maxPoints) throws IOException {
        validateRange(start, end);
        if (maxPoints < 100 || maxPoints > 5000) throw new IllegalArgumentException("maxPoints must be between 100 and 5000");
        double from = start.toEpochMilli() / 1000.0, to = end.toEpochMilli() / 1000.0;
        long bucketSeconds = Math.max(30, (long) Math.ceil((to - from) / maxPoints / 30) * 30);
        Map<Long, Bucket> buckets = new TreeMap<>();
        long[] total = {0};
        scan(start, end, point -> {
            double ts = point.path("timestamp").asDouble();
            long index = (long) ((ts - from) / bucketSeconds);
            buckets.computeIfAbsent(index, ignored -> new Bucket()).add(point);
            total[0]++;
        });
        List<ObjectNode> points = new ArrayList<>();
        Bucket previous = null;
        for (Bucket bucket : buckets.values()) {
            ObjectNode point = bucket.result();
            point.put("connectFromPrevious", previous != null && previous.continuous && bucket.continuous
                    && connected(previous.last, bucket.first));
            points.add(point);
            previous = bucket;
        }
        return Map.of("start", start.toString(), "end", end.toString(), "bucketSeconds", bucketSeconds,
                "totalRecords", total[0], "points", points, "storage", "persistent");
    }

    public void exportCsv(Instant start, Instant end, Writer out) throws IOException {
        validateRange(start, end);
        out.write(String.join(",", COLUMNS) + "\r\n");
        // One day's bounded records are read at a time, preserving the original measurements.
        for (LocalDate day = day(start.getEpochSecond()); !day.isAfter(day(end.getEpochSecond())); day = day.plusDays(1)) {
            List<ObjectNode> points = new ArrayList<>();
            read(day, p -> { if (inRange(p, start, end)) points.add(p); });
            points.sort(Comparator.comparingDouble(p -> p.path("timestamp").asDouble()));
            for (ObjectNode point : points) {
                List<String> row = new ArrayList<>();
                for (String key : COLUMNS) {
                    JsonNode item = point.get(key);
                    String text = item == null || item.isNull() ? "" : item.isTextual() ? item.asText() : item.toString();
                    row.add("\"" + text.replace("\"", "\"\"") + "\"");
                }
                out.write(String.join(",", row) + "\r\n");
            }
        }
    }

    public static void validateRange(Instant start, Instant end) {
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0
                || end.isAfter(Instant.parse("9999-12-31T23:59:59Z"))
                || start.isBefore(Instant.parse("2020-01-01T00:00:00Z")))
            throw new IllegalArgumentException("Choose a start before the end, from 2020 onward, spanning at most 366 days");
    }

    private void scan(Instant start, Instant end, Consumer<ObjectNode> accept) throws IOException {
        for (LocalDate day = day(start.getEpochSecond()); !day.isAfter(day(end.getEpochSecond())); day = day.plusDays(1)) {
            List<ObjectNode> points = new ArrayList<>();
            read(day, p -> { if (inRange(p, start, end)) points.add(p); });
            points.sort(Comparator.comparingDouble(p -> p.path("timestamp").asDouble()));
            points.forEach(accept);
        }
    }

    private static boolean inRange(JsonNode p, Instant start, Instant end) {
        double ts = p.path("timestamp").asDouble();
        return ts >= start.toEpochMilli() / 1000.0 && ts < end.toEpochMilli() / 1000.0;
    }

    private void read(LocalDate day, Consumer<ObjectNode> accept) throws IOException {
        Path path = file(day);
        if (!Files.exists(path)) return;
        Set<String> seen = new HashSet<>();
        try (BufferedReader in = Files.newBufferedReader(path)) {
            String line;
            while ((line = in.readLine()) != null) {
                ObjectNode point;
                try {
                    JsonNode parsed = mapper.readTree(line);
                    if (!(parsed instanceof ObjectNode) || !parsed.path("timestamp").isNumber()) continue;
                    point = (ObjectNode) parsed;
                } catch (IOException e) { continue; } // An interrupted final append is not a measurement.
                if (seen.add(id(point))) accept.accept(point);
            }
        }
    }

    private static void repairTail(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long end = channel.size();
            ByteBuffer one = ByteBuffer.allocate(1);
            while (end > 0) {
                one.clear();
                channel.read(one, end - 1);
                if (one.array()[0] == '\n') break;
                end--;
            }
            if (end != channel.size()) { channel.truncate(end); channel.force(true); }
        }
    }

    private Path file(LocalDate day) { return directory.resolve(day + ".jsonl"); }
    private static LocalDate day(double epoch) { return Instant.ofEpochSecond((long) epoch).atOffset(ZoneOffset.UTC).toLocalDate(); }
    private static String id(JsonNode point) { return point.path("sessionId").asText("legacy") + ":" + point.path("timestamp").asText(); }
    private static boolean ready(JsonNode point) { return READY.contains(point.path("status").asText()); }
    private static boolean connected(JsonNode a, JsonNode b) {
        double gap = b.path("timestamp").asDouble() - a.path("timestamp").asDouble();
        return ready(a) && ready(b) && gap >= 0 && gap <= 65
                && a.path("sessionId").equals(b.path("sessionId")) && a.path("segment").equals(b.path("segment"))
                && a.path("exposureUs").equals(b.path("exposureUs")) && a.path("gain").equals(b.path("gain"));
    }

    private class Bucket {
        ObjectNode first, last;
        long count;
        double times;
        boolean continuous = true;
        final Map<String, Double> sums = new HashMap<>();
        final Set<String> invalid = new HashSet<>();
        void add(ObjectNode point) {
            if (first == null) first = point;
            if (last != null && !connected(last, point)) continuous = false;
            last = point;
            count++;
            times += point.path("timestamp").asDouble();
            for (String metric : METRICS) {
                JsonNode value = point.path(metric);
                if (!ready(point) || !value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0) invalid.add(metric);
                else sums.merge(metric, value.asDouble(), Double::sum);
            }
        }
        ObjectNode result() {
            ObjectNode result = last.deepCopy();
            result.put("timestamp", times / count);
            result.put("records", count);
            for (String metric : METRICS) {
                if (!continuous || invalid.contains(metric)) result.putNull(metric);
                else result.put(metric, sums.getOrDefault(metric, 0.0) / count);
            }
            return result;
        }
    }
}
