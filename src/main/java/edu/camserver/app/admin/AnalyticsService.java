package edu.camserver.app.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.camserver.app.service.LiveStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Page views of the public site, for the admin panel.
 *
 * <p>The site's pages report each navigation ({@code POST /api/analytics/view}). A record keeps
 * the time, page, referring site and the visitor's approximate location (offline lookup, see
 * {@link GeoLocator}). The IP address itself is not stored: visitors are counted by a keyed
 * hash of address and browser that changes every day, so one person is one visitor per day and
 * cannot be followed across days. Records are UTC daily JSONL files in {@code app.analytics.dir}.
 * The peak number of simultaneous live-video viewers is sampled every 30 s.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final Pattern BOT = Pattern.compile("(?i)bot|crawl|spider|slurp|headless|preview|monitor|curl|wget|python|java/|go-http");
    private static final int MAX_VIEWS_PER_VISITOR_MINUTE = 60;

    private final ObjectMapper mapper;
    private final GeoLocator geo;
    private final LiveStreamService live;
    private final Path directory;
    private final ZoneId zone;
    private final Clock clock;
    private final NetworkAllowList vpn;
    private final Map<String, Deque<Instant>> recent = new HashMap<>();
    private final Map<LocalDate, List<ObjectNode>> closedDays = new LinkedHashMap<>();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "analytics-viewers");
        thread.setDaemon(true);
        return thread;
    });
    private byte[] secret;

    @Autowired
    public AnalyticsService(ObjectMapper mapper, GeoLocator geo, LiveStreamService live, AdminProperties admin,
                            @Value("${app.analytics.dir:./data/analytics}") String directory,
                            @Value("${app.analytics.zone:America/Los_Angeles}") String zone) {
        this(mapper, geo, live, admin, Path.of(directory), ZoneId.of(zone), Clock.systemUTC());
    }

    AnalyticsService(ObjectMapper mapper, GeoLocator geo, LiveStreamService live, AdminProperties admin,
                     Path directory, ZoneId zone, Clock clock) {
        this.mapper = mapper;
        this.geo = geo;
        this.live = live;
        this.directory = directory;
        this.zone = zone;
        this.clock = clock;
        this.vpn = new NetworkAllowList(admin.getAllowedNetworks());
    }

    /** Stores one page view; returns false when it was ignored (bot, bad input, flooding). */
    public synchronized boolean record(String address, String userAgent, String path, String referrer) {
        String page = normalizePath(path);
        if (page == null || page.startsWith("/admin") || userAgent == null || userAgent.isBlank() || BOT.matcher(userAgent).find()) {
            return false;
        }
        Instant now = clock.instant();
        LocalDate day = now.atOffset(ZoneOffset.UTC).toLocalDate();
        String visitor = visitor(day, address, userAgent);
        Deque<Instant> mine = recent.computeIfAbsent(visitor, v -> new ArrayDeque<>());
        while (!mine.isEmpty() && mine.peekFirst().isBefore(now.minusSeconds(60))) mine.pollFirst();
        if (mine.size() >= MAX_VIEWS_PER_VISITOR_MINUTE) return false;
        mine.addLast(now);
        if (recent.size() > 10_000) recent.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().peekLast().isBefore(now.minusSeconds(60)));

        GeoLocator.Location location = geo.locate(address);
        ObjectNode view = mapper.createObjectNode();
        view.put("ts", now.toString());
        view.put("path", page);
        view.put("visitor", visitor);
        view.put("countryCode", location.countryCode());
        view.put("country", location.country());
        view.put("region", location.region());
        view.put("city", location.city());
        view.put("network", network(address));
        String site = referrerSite(referrer);
        if (site != null) view.put("referrer", site);
        append(day, view);
        return true;
    }

    /** Own thread: Spring's single scheduler thread may be busy with an hours-long archive run. */
    @EventListener(ApplicationReadyEvent.class)
    public void startSampling() {
        sampler.scheduleWithFixedDelay(() -> {
            try {
                sampleViewers();
            } catch (RuntimeException e) {
                log.warn("Live viewer sample failed: {}", e.toString());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stopSampling() {
        sampler.shutdownNow();
    }

    void sampleViewers() {
        Object viewers = live.status().get("viewers");
        if (!(viewers instanceof Number count) || count.intValue() <= 0) return;
        synchronized (this) {
            Instant now = clock.instant();
            ObjectNode sample = mapper.createObjectNode();
            sample.put("ts", now.toString());
            sample.put("liveViewers", count.intValue());
            append(now.atOffset(ZoneOffset.UTC).toLocalDate(), sample);
        }
    }

    /** Totals, per-day counts, pages, regions and recent views for the last {@code days} local days. */
    public synchronized Map<String, Object> summary(int days) throws IOException {
        int span = Math.max(1, Math.min(days, 366));
        Instant now = clock.instant();
        LocalDate lastLocal = now.atZone(zone).toLocalDate();
        LocalDate firstLocal = lastLocal.minusDays(span - 1L);
        Instant from = firstLocal.atStartOfDay(zone).toInstant();

        Map<LocalDate, long[]> perDay = new TreeMap<>();   // views, peak live viewers
        Map<LocalDate, Set<String>> visitorsPerDay = new TreeMap<>();
        for (LocalDate d = firstLocal; !d.isAfter(lastLocal); d = d.plusDays(1)) {
            perDay.put(d, new long[2]);
            visitorsPerDay.put(d, new HashSet<>());
        }
        Map<String, long[]> pages = new HashMap<>();
        Map<String, Map<String, Object>> regions = new HashMap<>();
        Map<String, Set<String>> regionVisitors = new HashMap<>();
        Map<String, long[]> referrers = new HashMap<>();
        Map<String, long[]> networks = new HashMap<>();
        Deque<JsonNode> latest = new ArrayDeque<>();
        long views = 0;

        for (LocalDate utcDay = from.atOffset(ZoneOffset.UTC).toLocalDate(); !utcDay.isAfter(now.atOffset(ZoneOffset.UTC).toLocalDate());
             utcDay = utcDay.plusDays(1)) {
            for (ObjectNode record : read(utcDay, now)) {
                Instant ts = Instant.parse(record.path("ts").asText());
                if (ts.isBefore(from) || ts.isAfter(now)) continue;
                LocalDate local = ts.atZone(zone).toLocalDate();
                long[] day = perDay.get(local);
                if (day == null) continue;
                if (record.has("liveViewers")) {
                    day[1] = Math.max(day[1], record.path("liveViewers").asLong());
                    continue;
                }
                views++;
                day[0]++;
                String visitor = record.path("visitor").asText();
                visitorsPerDay.get(local).add(visitor);
                pages.computeIfAbsent(record.path("path").asText(), p -> new long[1])[0]++;
                String key = record.path("countryCode").asText("") + "|" + record.path("region").asText("");
                Map<String, Object> region = regions.computeIfAbsent(key, k -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("countryCode", text(record, "countryCode"));
                    r.put("country", text(record, "country"));
                    r.put("region", text(record, "region"));
                    r.put("views", 0L);
                    r.put("cities", new TreeMap<String, Long>());
                    return r;
                });
                region.put("views", (Long) region.get("views") + 1);
                String city = text(record, "city");
                if (city != null) {
                    @SuppressWarnings("unchecked") Map<String, Long> cities = (Map<String, Long>) region.get("cities");
                    cities.merge(city, 1L, Long::sum);
                }
                regionVisitors.computeIfAbsent(key, k -> new HashSet<>()).add(local + visitor);
                if (record.hasNonNull("referrer")) referrers.computeIfAbsent(record.path("referrer").asText(), r -> new long[1])[0]++;
                networks.computeIfAbsent(record.path("network").asText("external"), n -> new long[1])[0]++;
                latest.addLast(record);
                if (latest.size() > 50) latest.pollFirst();
            }
        }

        List<Map<String, Object>> daily = new ArrayList<>();
        long visitors = 0;
        long peakViewers = 0;
        for (var entry : perDay.entrySet()) {
            int unique = visitorsPerDay.get(entry.getKey()).size();
            visitors += unique;
            peakViewers = Math.max(peakViewers, entry.getValue()[1]);
            daily.add(Map.of("date", entry.getKey().toString(), "views", entry.getValue()[0], "visitors", unique,
                    "peakLiveViewers", entry.getValue()[1]));
        }
        List<Map<String, Object>> regionList = new ArrayList<>();
        regions.forEach((key, region) -> {
            region.put("visitors", regionVisitors.getOrDefault(key, Set.of()).size());
            regionList.add(region);
        });
        regionList.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("views")).reversed());
        List<Map<String, Object>> recentViews = new ArrayList<>();
        latest.descendingIterator().forEachRemaining(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ts", r.path("ts").asText());
            item.put("path", r.path("path").asText());
            item.put("country", text(r, "country"));
            item.put("region", text(r, "region"));
            item.put("city", text(r, "city"));
            item.put("network", text(r, "network"));
            recentViews.add(item);
        });
        LocalDate today = lastLocal;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", firstLocal.toString());
        result.put("to", lastLocal.toString());
        result.put("timeZone", zone.getId());
        result.put("views", views);
        result.put("visitors", visitors);
        result.put("todayViews", perDay.get(today)[0]);
        result.put("todayVisitors", visitorsPerDay.get(today).size());
        result.put("peakLiveViewers", peakViewers);
        result.put("liveViewersNow", live.status().getOrDefault("viewers", 0));
        result.put("daily", daily);
        result.put("pages", top(pages, 20, "path"));
        result.put("regions", regionList.size() > 100 ? regionList.subList(0, 100) : regionList);
        result.put("referrers", top(referrers, 15, "site"));
        result.put("networks", top(networks, 5, "network"));
        result.put("recent", recentViews);
        result.put("geoDatabase", geo.databaseDescription());
        return result;
    }

    // ------------------------------------------------------------------------------------ helpers

    static String normalizePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.startsWith("//")) return null;
        int cut = path.length();
        for (char c : new char[] {'?', '#'}) {
            int at = path.indexOf(c);
            if (at >= 0) cut = Math.min(cut, at);
        }
        String page = path.substring(0, cut);
        if (page.length() > 120 || !page.chars().allMatch(c -> c > 32 && c < 127)) return null;
        // Image pages carry an id; count them as one page.
        return page.replaceAll("/\\d+(?=/|$)", "/:id");
    }

    static String referrerSite(String referrer) {
        if (referrer == null || referrer.isBlank() || referrer.length() > 2048) return null;
        try {
            String host = URI.create(referrer.trim()).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            return host.endsWith("armageddon.deepspace.ucsb.edu") || host.endsWith("camserver.physics.ucsb.edu") ? null : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String network(String address) {
        if (address == null) return "external";
        if (vpn.match(address).isPresent()) return "ucsb-vpn";
        if (address.startsWith("128.111.") || address.startsWith("169.231.")) return "ucsb-campus";
        return "external";
    }

    private String visitor(LocalDate day, String address, String userAgent) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
            byte[] digest = mac.doFinal((day + "|" + address + "|" + userAgent).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot derive visitor id", e);
        }
    }

    private byte[] secret() throws IOException {
        if (secret != null) return secret;
        Path file = directory.resolve(".visitor-key");
        Files.createDirectories(directory);
        if (Files.exists(file)) {
            secret = Base64.getDecoder().decode(Files.readString(file).trim());
        } else {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.writeString(file, Base64.getEncoder().encodeToString(key));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX file system (tests on other platforms).
            }
            secret = key;
        }
        return secret;
    }

    private void append(LocalDate day, ObjectNode record) {
        try {
            Files.createDirectories(directory);
            try (FileChannel out = FileChannel.open(directory.resolve(day + ".jsonl"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(mapper.writeValueAsString(record) + "\n");
                while (bytes.hasRemaining()) out.write(bytes);
            }
            closedDays.remove(day);
        } catch (IOException e) {
            log.warn("Cannot store page view in {}: {}", directory, e.toString());
        }
    }

    /** Past UTC days never change, so they are parsed once and kept (a few recent ones). */
    private List<ObjectNode> read(LocalDate day, Instant now) throws IOException {
        List<ObjectNode> cached = closedDays.get(day);
        if (cached != null) return cached;
        Path file = directory.resolve(day + ".jsonl");
        List<ObjectNode> records = new ArrayList<>();
        if (Files.exists(file)) {
            try (BufferedReader in = Files.newBufferedReader(file)) {
                String line;
                while ((line = in.readLine()) != null) {
                    try {
                        if (mapper.readTree(line) instanceof ObjectNode node && node.hasNonNull("ts")) records.add(node);
                    } catch (IOException ignored) {
                        // A torn final line after a crash is not a view.
                    }
                }
            }
        }
        if (day.isBefore(now.atOffset(ZoneOffset.UTC).toLocalDate())) {
            closedDays.put(day, records);
            while (closedDays.size() > 40) closedDays.remove(closedDays.keySet().iterator().next());
        }
        return records;
    }

    private static List<Map<String, Object>> top(Map<String, long[]> counts, int limit, String label) {
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(limit)
                .map(e -> Map.<String, Object>of(label, e.getKey(), "views", e.getValue()[0]))
                .toList();
    }

    private static String text(JsonNode record, String field) {
        JsonNode value = record.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
