package edu.camserver.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.io.StringWriter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SeeingHistoryServiceTest {
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();
    final Instant start = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);

    SeeingHistoryService store() { return new SeeingHistoryService(mapper, dir.toString()); }
    Map<String, Object> point(int offset, String status, int segment, String session) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("timestamp", start.getEpochSecond() + offset);
        p.put("status", status); p.put("segment", segment); p.put("sessionId", session);
        p.put("jitterRmsPx", 2.0); p.put("seeingArcsec", null); p.put("fluxVariationPercent", 5.0);
        p.put("exposureUs", 10000); p.put("gain", 1); p.put("samples", 300);
        p.put("driftPxPerMinute", 0.7); p.put("fwhmPx", 3.5); p.put("flux", 1500);
        p.put("backgroundRms", 0.25); p.put("durationSeconds", 60);
        return p;
    }
    JsonNode query(SeeingHistoryService s, int duration) throws Exception {
        return mapper.valueToTree(s.query(start, start.plusSeconds(duration), 100));
    }

    @Test void survivesRestartDeduplicatesReplayAndExportsOriginals() throws Exception {
        var payload = Map.of("history", List.of(point(0,"relative",1,"a"), point(30,"relative",1,"a")));
        assertTrue(store().record(payload));
        var reopened = store();
        assertTrue(reopened.record(payload));
        var result = query(reopened, 60);
        assertEquals(2, result.path("totalRecords").asInt());
        assertTrue(result.path("points").get(1).path("connectFromPrevious").asBoolean());
        StringWriter csv = new StringWriter(); reopened.exportCsv(start, start.plusSeconds(60), csv);
        assertEquals(3, csv.toString().lines().count());
        assertTrue(csv.toString().contains("sessionId"));
        assertEquals(22,csv.toString().lines().findFirst().orElseThrow().split(",").length);
        assertEquals(0.7,result.path("points").get(0).path("driftPxPerMinute").asDouble());
        assertTrue(csv.toString().contains("\"3.5\""));
        assertFalse(csv.toString().contains("NaN"));
    }

    @Test void repairsTornAppendBeforeWritingNewData() throws Exception {
        assertTrue(store().record(Map.of("history", List.of(point(0,"relative",1,"a")))));
        Path journal = Files.list(dir).filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        Files.writeString(journal,"{\"timestamp\":",StandardOpenOption.APPEND);
        assertTrue(store().record(Map.of("history", List.of(point(30,"relative",1,"a")))));
        assertEquals(2, query(store(),60).path("totalRecords").asInt());
        assertEquals(2, Files.readAllLines(journal).size());
    }

    @Test void neverConnectsAcrossInvalidDataSettingsSessionsOrMissingTime() throws Exception {
        var points = List.of(point(0,"relative",1,"a"),point(30,"searching",1,"a"),
                point(60,"relative",1,"a"),point(90,"relative",1,"b"),
                point(120,"relative",2,"b"),point(240,"relative",2,"b"));
        store().record(Map.of("history",points));
        for (JsonNode p : query(store(),300).path("points")) assertFalse(p.path("connectFromPrevious").asBoolean());
        assertTrue(query(store(),300).path("points").get(1).path("jitterRmsPx").isNull());
    }

    @Test void bucketAveragesAreBoundedAndDoNotAverageAcrossResets() throws Exception {
        List<Map<String,Object>> points = new ArrayList<>();
        for(int i=0;i<120;i++) points.add(point(i*30,"relative",i<2?1:2,"a"));
        store().record(Map.of("history",points));
        var result = query(store(),6000);
        assertEquals(60,result.path("bucketSeconds").asInt());
        assertTrue(result.path("points").size()<=100);
        assertEquals(2,result.path("points").get(0).path("records").asInt());
        assertEquals(2,result.path("points").get(0).path("jitterRmsPx").asDouble());
        assertFalse(result.path("points").get(1).path("connectFromPrevious").asBoolean());
        assertEquals(120,result.path("totalRecords").asInt());
    }

    @Test void rangeIsHalfOpenAndInputIsValidated() throws Exception {
        store().record(Map.of("history",List.of(point(0,"relative",1,"a"),point(30,"relative",1,"a"))));
        assertEquals(1,query(store(),30).path("totalRecords").asInt());
        assertThrows(IllegalArgumentException.class,()->store().query(start,start,100));
        assertThrows(IllegalArgumentException.class,()->store().query(start,start.plusSeconds(367L*86400),100));
        assertThrows(IllegalArgumentException.class,()->store().query(start,start.plusSeconds(60),5001));
    }

    @Test void resetWithinAnAveragingBucketProducesAGap() throws Exception {
        store().record(Map.of("history",List.of(point(0,"relative",1,"a"),point(30,"relative",2,"a"),
                point(60,"relative",2,"a"),point(90,"relative",2,"a"))));
        var points=query(store(),6000).path("points");
        assertTrue(points.get(0).path("jitterRmsPx").isNull());
        assertFalse(points.get(1).path("connectFromPrevious").asBoolean());
        assertEquals(2,points.get(1).path("jitterRmsPx").asDouble());
    }

    @Test void storageFailureIsNotAcknowledged() throws Exception {
        Path blocked=dir.resolve("file"); Files.writeString(blocked,"not a directory");
        var broken=new SeeingHistoryService(mapper,blocked.toString());
        assertFalse(broken.record(Map.of("history",List.of(point(0,"relative",1,"a")))));
    }
}
