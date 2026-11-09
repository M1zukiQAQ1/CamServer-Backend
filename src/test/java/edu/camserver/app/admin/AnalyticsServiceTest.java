package edu.camserver.app.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.camserver.app.service.LiveStreamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {
    @TempDir Path dir;

    private static final String BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 Safari/605.1.15";

    private AnalyticsService service(AdminAuthServiceTest.TestClock clock) {
        LiveStreamService live = mock(LiveStreamService.class);
        when(live.status()).thenReturn(Map.of("viewers", 2));
        AdminProperties admin = new AdminProperties();
        admin.setAllowedNetworks(List.of("128.111.180.0/22"));
        return new AnalyticsService(new ObjectMapper(), new GeoLocator(dir.resolve("none.mmdb").toString()), live, admin,
                dir.resolve("analytics"), ZoneId.of("America/Los_Angeles"), clock);
    }

    @Test void countsViewsVisitorsPagesAndNetworksWithoutStoringAddresses() throws Exception {
        AdminAuthServiceTest.TestClock clock = new AdminAuthServiceTest.TestClock();
        AnalyticsService analytics = service(clock);
        assertTrue(analytics.record("128.111.180.9", BROWSER, "/seeing-monitor?x=1", "https://www.google.com/search?q=ucsb"));
        assertTrue(analytics.record("128.111.180.9", BROWSER, "/gallery/12345", null));
        assertTrue(analytics.record("203.0.113.7", BROWSER, "/", "https://armageddon.deepspace.ucsb.edu/health"));
        assertFalse(analytics.record("203.0.113.8", "Googlebot/2.1", "/", null), "bots are not visitors");
        assertFalse(analytics.record("203.0.113.8", BROWSER, "/admin", null), "the admin panel is not counted");
        assertFalse(analytics.record("203.0.113.8", BROWSER, "https://evil.example/", null));

        Map<String, Object> summary = analytics.summary(7);
        assertEquals(3L, summary.get("views"));
        assertEquals(2L, summary.get("visitors"));
        assertEquals(3L, summary.get("todayViews"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> pages = (List<Map<String, Object>>) summary.get("pages");
        assertTrue(pages.stream().anyMatch(p -> "/gallery/:id".equals(p.get("path"))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> referrers = (List<Map<String, Object>>) summary.get("referrers");
        assertEquals("www.google.com", referrers.get(0).get("site"));
        assertEquals(1, referrers.size(), "the site's own pages are not referrers");
        @SuppressWarnings("unchecked") List<Map<String, Object>> networks = (List<Map<String, Object>>) summary.get("networks");
        assertTrue(networks.contains(Map.of("network", "ucsb-vpn", "views", 2L)));

        String stored = String.join("", Files.readAllLines(dir.resolve("analytics").resolve("2026-09-28.jsonl")));
        assertFalse(stored.contains("128.111.180.9") || stored.contains("203.0.113.7"), "no IP addresses on disk");
    }

    @Test void visitorIdsChangeEveryDayAndFloodingIsCapped() throws Exception {
        AdminAuthServiceTest.TestClock clock = new AdminAuthServiceTest.TestClock();
        AnalyticsService analytics = service(clock);
        int accepted = 0;
        for (int i = 0; i < 80; i++) if (analytics.record("203.0.113.7", BROWSER, "/", null)) accepted++;
        assertEquals(60, accepted);
        String day1 = Files.readAllLines(dir.resolve("analytics").resolve("2026-09-28.jsonl")).get(0);
        clock.now = clock.now.plus(Duration.ofDays(1));
        analytics.record("203.0.113.7", BROWSER, "/", null);
        String day2 = Files.readAllLines(dir.resolve("analytics").resolve("2026-09-29.jsonl")).get(0);
        ObjectMapper json = new ObjectMapper();
        assertNotEquals(json.readTree(day1).path("visitor"), json.readTree(day2).path("visitor"));
        assertEquals(2L, analytics.summary(7).get("visitors"), "one visitor per day");
    }
}
