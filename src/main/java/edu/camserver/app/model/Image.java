package edu.camserver.app.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;


@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "Images", schema = "dbo")
@Getter
@Setter
@ToString
public class Image {

    @Id
    @Column(unique = true, name = "ImgId")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long imgId;

    @Column(name = "cameraId")
    private String cameraId;

    @Column(name = "siteName")
    private String siteName;

    @Column(name = "Timestamp")
    private LocalDateTime timestamp;

    @Column(name = "BitDepth")
    private int bit;

    @Column(name = "Gain")
    private int gain;

    @Column(name = "ExpTime")
    private int exposure;

    @Column(name = "ImgPath")
    private String imgPath;

    @Column(name = "Temperature")
    private float temperature;

    @Column(name = "Humidity")
    private float humidity;

    @Column(name = "TimeZone")
    private String timeZone;

    @Column(name = "Feat")
    private boolean featured;

    public Image(String cameraId, String siteName, LocalDateTime timestamp, int bit, int gain, int exposure, String imgPath, float temperature, float humidity, String timeZone, boolean featured) {
        this.cameraId = cameraId;
        this.siteName = siteName;
        this.timestamp = timestamp;
        this.bit = bit;
        this.gain = gain;
        this.exposure = exposure;
        this.imgPath = imgPath;
        this.temperature = temperature;
        this.humidity = humidity;
        this.timeZone = timeZone;
        this.featured = featured;
    }
}
