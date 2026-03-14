package edu.camserver.app.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;


@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "Images", schema = "dbo")
@Getter
@Setter
public class Image {

    @Id
    @Column(unique = true, name = "ImgId")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long imgId;

    @Column(name = "cameraId")
    private String cameraId;

    // Not Used?
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

    public Image(String cameraId, String siteName, LocalDateTime timestamp, int bit, int gain, int exposure, String imgPath, float temperature, float humidity, String timeZone) {
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
    }
}
