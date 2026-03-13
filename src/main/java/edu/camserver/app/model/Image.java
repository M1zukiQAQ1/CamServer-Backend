package edu.camserver.app.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;


@Entity
@NoArgsConstructor
@Table(name = "Images", schema = "dbo")
@Getter
@Setter
public class Image {

    @Id
    @Column(unique = true, nullable = false, name = "ImgId")
    private int imgId;

    private String cameraId;

    // Not Used?
    private String siteName;

    @Column(name = "Timestamp")
    private LocalDateTime dateTime;

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

    public Image(String cameraId, String siteName, LocalDateTime dateTime, int bit, int gain, int exposure, String imgPath, float temperature, float humidity, String timeZone) {
        this.cameraId = cameraId;
        this.siteName = siteName;
        this.dateTime = dateTime;
        this.bit = bit;
        this.gain = gain;
        this.exposure = exposure;
        this.imgPath = imgPath;
        this.temperature = temperature;
        this.humidity = humidity;
        this.timeZone = timeZone;
    }
}
