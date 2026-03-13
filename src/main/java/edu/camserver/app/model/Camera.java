package edu.camserver.app.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "Cameras", schema = "dbo")
@Getter
@Setter
public class Camera {

    @Id
    @Column(unique = true, nullable = false, name = "UID")
    private int UID;

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