package edu.camserver.app.admin;

import com.maxmind.db.Reader;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

/**
 * Offline IP geolocation from an MMDB file — by default DB-IP's free "IP to City Lite"
 * (CC BY 4.0, https://db-ip.com), updated monthly. No address ever leaves this server.
 * A replaced file is picked up on the next lookup; without one, locations are "Unknown".
 */
@Component
public class GeoLocator {

    private static final Logger log = LoggerFactory.getLogger(GeoLocator.class);

    public record Location(String countryCode, String country, String region, String city) {
        static final Location UNKNOWN = new Location(null, "Unknown", null, null);
    }

    private final Path database;
    private Reader reader;
    private FileTime loaded;
    private boolean warned;

    public GeoLocator(@Value("${app.analytics.geoip-database:./data/geoip/dbip-city-lite.mmdb}") String database) {
        this.database = Path.of(database);
    }

    public synchronized Location locate(String address) {
        // Numeric literals only, so InetAddress.getByName never turns into a DNS lookup.
        boolean literal = address != null && (NetworkAllowList.parseIpv4(address).isPresent()
                || address.contains(":") && address.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.'));
        if (!literal) return Location.UNKNOWN;
        try {
            Reader current = reader();
            if (current == null) return Location.UNKNOWN;
            @SuppressWarnings("unchecked")
            Map<String, Object> record = current.get(InetAddress.getByName(address), Map.class);
            if (record == null) return Location.UNKNOWN;
            Map<?, ?> country = map(record.get("country"));
            String region = null;
            if (record.get("subdivisions") instanceof List<?> subdivisions && !subdivisions.isEmpty()) {
                region = name(map(subdivisions.get(0)));
            }
            return new Location(country.get("iso_code") instanceof String code ? code : null,
                    nonNull(name(country), "Unknown"), region, name(map(record.get("city"))));
        } catch (IOException | RuntimeException e) {
            log.debug("Geolocation of {} failed: {}", address, e.toString());
            return Location.UNKNOWN;
        }
    }

    public synchronized String databaseDescription() {
        try {
            Reader current = reader();
            if (current == null) return null;
            return current.getMetadata().getDatabaseType() + " built " + current.getMetadata().getBuildDate().toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    private Reader reader() throws IOException {
        if (!Files.isRegularFile(database)) {
            if (!warned) log.warn("No geolocation database at {}; visitor regions show as Unknown", database.toAbsolutePath());
            warned = true;
            return null;
        }
        FileTime modified = Files.getLastModifiedTime(database);
        if (reader == null || !modified.equals(loaded)) {
            if (reader != null) reader.close();
            reader = new Reader(new File(database.toString()));
            loaded = modified;
            log.info("Geolocation database {} loaded: {}", database.toAbsolutePath(), reader.getMetadata().getDatabaseType());
        }
        return reader;
    }

    @PreDestroy
    public synchronized void close() throws IOException {
        if (reader != null) reader.close();
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String name(Map<?, ?> value) {
        return map(value.get("names")).get("en") instanceof String name ? name : null;
    }

    private static String nonNull(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
