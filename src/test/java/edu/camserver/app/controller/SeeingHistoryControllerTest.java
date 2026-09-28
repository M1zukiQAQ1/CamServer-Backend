package edu.camserver.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.camserver.app.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SeeingHistoryControllerTest {
    @TempDir Path dir;
    @Test void rejectsUnauthenticatedHistoryIngestion() throws Exception {
        SeeingHistoryService history = new SeeingHistoryService(new ObjectMapper(),dir.toString()) {
            @Override public boolean record(Object value) { throw new AssertionError("Unauthorized write"); }
        };
        var controller = new LiveStreamController(null,new FrameService(),null,history,"test-token",100000);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/live/telemetry").contentType("application/json")
                .content("{\"seeing\":{\"history\":[]}}"))
                .andExpect(status().isUnauthorized());
    }
    @Test void supportsEmptyRangeAndDownloadAndRejectsBadDates() throws Exception {
        var controller = new SeeingHistoryController(new SeeingHistoryService(new ObjectMapper(),dir.toString()));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        String start=Instant.now().minusSeconds(3600).toString(), end=Instant.now().toString();
        mvc.perform(get("/api/live/seeing/history").param("start",start).param("end",end))
                .andExpect(status().isOk()).andExpect(jsonPath("$.storage").value("persistent"))
                .andExpect(jsonPath("$.totalRecords").value(0));
        mvc.perform(get("/api/live/seeing/history.csv").param("start",start).param("end",end))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=seeing-history.csv"));
        mvc.perform(get("/api/live/seeing/history").param("start","bad").param("end",end)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/live/seeing/history").param("start",end).param("end",start)).andExpect(status().isBadRequest());
    }

    @Test void authenticatedTelemetryAcknowledgesDurableHistory() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        var history=new SeeingHistoryService(mapper,dir.resolve("history").toString());
        var settings=new SettingsService(mapper,dir.resolve("settings.json").toString(),10000,1);
        var controller=new LiveStreamController(null,new FrameService(),settings,history,"test-token",100000);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        long ts=Instant.now().minusSeconds(10).getEpochSecond();
        mvc.perform(post("/api/live/telemetry").header("X-Live-Token","test-token").contentType("application/json")
                .content("{\"seeing\":{\"history\":[{\"timestamp\":"+ts+",\"sessionId\":\"test\",\"status\":\"relative\",\"jitterRmsPx\":1}]}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.historyStored").value(true));
        var reopened=new SeeingHistoryService(mapper,dir.resolve("history").toString());
        org.junit.jupiter.api.Assertions.assertEquals(1L,reopened.query(Instant.ofEpochSecond(ts),Instant.now(),100).get("totalRecords"));
    }
}
