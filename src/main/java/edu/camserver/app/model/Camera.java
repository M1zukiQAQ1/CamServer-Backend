package edu.camserver.app.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Camera {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(unique = true, name = "CamId")
    private String cameraId;

    @Column(name = "SiteName")
    private String siteName;

    @Column(name = "TimeZone")
    private String timeZone;

    // Will change from GeoLoc to longitude and latitude for better readability
    @Column(name = "Longitude")
    private Double longitude;

    @Column(name = "Lat")
    private Double latitude;

}