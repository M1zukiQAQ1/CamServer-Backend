package edu.camserver.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Station coordinates for cameras whose database location has not been populated. */
record ObservingSite(String cameraId, double latitudeDeg, double longitudeDeg) {
    private static final List<ObservingSite> SITES = load();

    static Optional<ObservingSite> find(String cameraId) {
        return SITES.stream().filter(site -> site.cameraId.equals(cameraId)).findFirst();
    }

    private static List<ObservingSite> load() {
        try (var input = new ClassPathResource("catalogs/observing-sites.json").getInputStream()) {
            var sites = Arrays.asList(new ObjectMapper().readValue(input, ObservingSite[].class));
            if (sites.stream().anyMatch(site -> site.cameraId == null || site.cameraId.isBlank()
                    || !Double.isFinite(site.latitudeDeg + site.longitudeDeg)
                    || Math.abs(site.latitudeDeg) > 90 || Math.abs(site.longitudeDeg) > 180)) {
                throw new IllegalStateException("Invalid observing-site coordinates");
            }
            return List.copyOf(sites);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read observing-site coordinates", e);
        }
    }
}
