package edu.camserver.app.model;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ImageFilter {
    private Boolean featured;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String siteName;
    private String search;
    private String period;
}
