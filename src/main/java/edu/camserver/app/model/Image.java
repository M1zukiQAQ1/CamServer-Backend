package edu.camserver.app.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.sql.Timestamp;
import java.util.Date;


@Entity
@Getter
@Setter
@NoArgsConstructor
public class Image {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(unique = true, nullable = false, name = "ImgId")
    private int imgId;

    @Column(name = "CamId")
    private String cameraId;

    // Not Used?
    private String siteName;

    @Column(name = "Timestamp")
    private Timestamp ts;

    @Column(name = "BitDepth")
    private int bit;

    @Column(name = "Gain")
    private int gain;

    @Column(name = "ExpTime")
    private int exposure;

    @Column(name = "ImgPath")
    private String filePath;

    @Column(name = "Temperature")
    private float temperature;

    @Column(name = "Humidity")
    private float humidity;


    @Column(name = "TimeZone")
    private String timeZone;
}
