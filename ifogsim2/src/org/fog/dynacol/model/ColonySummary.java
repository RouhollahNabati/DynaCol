package org.fog.dynacol.model;

/**
 * Summarized neighbor-colony view stored in the Global Resource Table (GRT).
 */
public class ColonySummary {

    private final long colonyId;
    private final int fcmDeviceId;
    private ResourceVector aggregateAvailable;
    private double attractiveness;
    private double rttToLocalFcmMs;

    public ColonySummary(long colonyId, int fcmDeviceId, ResourceVector aggregateAvailable, double rttToLocalFcmMs) {
        this.colonyId = colonyId;
        this.fcmDeviceId = fcmDeviceId;
        this.aggregateAvailable = aggregateAvailable;
        this.rttToLocalFcmMs = rttToLocalFcmMs;
        this.attractiveness = 0.0;
    }

    public long getColonyId() {
        return colonyId;
    }

    public int getFcmDeviceId() {
        return fcmDeviceId;
    }

    public ResourceVector getAggregateAvailable() {
        return aggregateAvailable;
    }

    public void setAggregateAvailable(ResourceVector aggregateAvailable) {
        this.aggregateAvailable = aggregateAvailable;
    }

    public double getAttractiveness() {
        return attractiveness;
    }

    public void setAttractiveness(double attractiveness) {
        this.attractiveness = attractiveness;
    }

    public double getRttToLocalFcmMs() {
        return rttToLocalFcmMs;
    }
}
