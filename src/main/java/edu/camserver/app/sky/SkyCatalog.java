package edu.camserver.app.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference data bundled with the application, all in J2000 coordinates:
 * <ul>
 *   <li>{@code catalogs/sky-stars.csv}: every star to magnitude 5.0 (Yale Bright Star Catalogue),
 *       the stars a clear sky should show;</li>
 *   <li>{@code catalogs/bright-stars.csv}: the named bright stars, for labels;</li>
 *   <li>{@code catalogs/constellations.json}: constellation figures from d3-celestial (BSD-3-Clause).</li>
 * </ul>
 */
@Component
public class SkyCatalog {

    public record Star(String name, double raDeg, double decDeg, double magnitude) {
    }

    public record Figure(String id, String name, double[] label, List<List<double[]>> lines) {
    }

    private final List<Star> stars;
    private final List<Star> namedStars;
    private final List<Figure> figures;

    public SkyCatalog(ObjectMapper mapper) {
        this.stars = readStars("catalogs/sky-stars.csv");
        this.namedStars = readStars("catalogs/bright-stars.csv");
        this.figures = readFigures(mapper);
    }

    /** Stars to magnitude 5.0, brightest first; names are null. */
    public List<Star> stars() {
        return stars;
    }

    public List<Star> namedStars() {
        return namedStars;
    }

    public List<Figure> figures() {
        return figures;
    }

    private static List<Star> readStars(String resource) {
        List<Star> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8))) {
            List<String> header = List.of(reader.readLine().split(","));
            int name = header.indexOf("name");
            int ra = header.indexOf("raDeg");
            int dec = header.indexOf("decDeg");
            int magnitude = header.indexOf("magnitude");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length <= Math.max(ra, Math.max(dec, magnitude))) {
                    continue;
                }
                try {
                    result.add(new Star(name < 0 ? null : parts[name], Double.parseDouble(parts[ra]),
                            Double.parseDouble(parts[dec]), Double.parseDouble(parts[magnitude])));
                } catch (NumberFormatException ignored) {
                    // a row without a magnitude or position is of no use here
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
        return List.copyOf(result);
    }

    private static List<Figure> readFigures(ObjectMapper mapper) {
        List<Figure> result = new ArrayList<>();
        try (InputStream in = new ClassPathResource("catalogs/constellations.json").getInputStream()) {
            for (JsonNode node : mapper.readTree(in).get("constellations")) {
                List<List<double[]>> lines = new ArrayList<>();
                for (JsonNode line : node.get("lines")) {
                    List<double[]> points = new ArrayList<>();
                    for (JsonNode point : line) {
                        points.add(new double[] {point.get(0).asDouble(), point.get(1).asDouble()});
                    }
                    lines.add(points);
                }
                JsonNode label = node.get("label");
                result.add(new Figure(node.get("id").asText(), node.get("name").asText(),
                        new double[] {label.get(0).asDouble(), label.get(1).asDouble()}, lines));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read catalogs/constellations.json", e);
        }
        return List.copyOf(result);
    }
}
