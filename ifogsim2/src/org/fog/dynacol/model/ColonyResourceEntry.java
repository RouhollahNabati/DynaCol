package org.fog.dynacol.model;

/**
 * Single entry in the Colony Resource Table (CRT).
 */
public class ColonyResourceEntry {

    private final int fogDeviceId;
    private ResourceVector available;
    private double attractiveness;
    private double rttToFcmMs;

    public ColonyResourceEntry(int fogDeviceId, ResourceVector available, double rttToFcmMs) {
        this.fogDeviceId = fogDeviceId;
        this.available = available;
        this.rttToFcmMs = rttToFcmMs;
        this.attractiveness = 0.0;
    }

    public int getFogDeviceId() {
        return fogDeviceId;
    }

    public ResourceVector getAvailable() {
        return available;
    }

    public void setAvailable(ResourceVector available) {
        this.available = available;
    }

    public double getAttractiveness() {
        return attractiveness;
    }

    public void setAttractiveness(double attractiveness) {
        this.attractiveness = attractiveness;
    }

    public double getRttToFcmMs() {
        return rttToFcmMs;
    }
}
