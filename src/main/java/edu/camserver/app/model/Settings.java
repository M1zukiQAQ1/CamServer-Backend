package edu.camserver.app.model;

public class Settings {
    private int exposure;   // Exposure Time
    private int gain;

    public Settings(int exposure, int gain) {
        this.exposure = exposure;
        this.gain = gain;
    }

    public int getExposure() { return exposure; }
    public void setExposure(int exposure) { this.exposure = exposure; }

    public int getGain() { return gain; }
    public void setGain(int gain) { this.gain = gain; }
}
