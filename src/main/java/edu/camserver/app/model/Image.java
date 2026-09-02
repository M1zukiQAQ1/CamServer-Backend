package edu.camserver.app.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Formula;

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

    // CamId is a fixed-width char column, so values come back space-padded.
    @Column(name = "CamId")
    @Getter(AccessLevel.NONE)
    private String cameraId;

    // Derived from the Cameras table; there is no site name stored on the image row itself.
    @Formula("(SELECT c.SiteName FROM Cameras c WHERE c.CamId = CamId)")
    @Setter(AccessLevel.NONE)
    private String siteName;

    public String getCameraId() {
        return cameraId == null ? null : cameraId.trim();
    }

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

    // Legacy rows have NULL here; treat that as "not featured" instead of failing to load.
    @Column(name = "Feat")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean featured;

    public boolean isFeatured() {
        return Boolean.TRUE.equals(featured);
    }

    public void setFeatured(boolean featured) {
        this.featured = featured;
    }

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
