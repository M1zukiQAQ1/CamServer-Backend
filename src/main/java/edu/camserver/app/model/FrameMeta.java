package edu.camserver.app.model;

import jakarta.persistence.Entity;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Entity
// Compile will not go through with AllArgsConstructor and NoArgsContructor
public class FrameMeta {
    private Double clientTs;
    private Double serverTs;
    private Integer latencyMs;
    private String tsFormat;

    public FrameMeta() {
    }

    public FrameMeta(Double clientTs, Double serverTs, Integer latencyMs, String tsFormat) {
        this.clientTs = clientTs;
        this.serverTs = serverTs;
        this.latencyMs = latencyMs;
        this.tsFormat = tsFormat;
    }
}
