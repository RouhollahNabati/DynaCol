package org.fog.dynacol.metrics;

/**
 * Counts control-plane messages for overhead evaluation.
 */
public class ControlOverheadMonitor {

    private static final ControlOverheadMonitor INSTANCE = new ControlOverheadMonitor();

    private long queryMessages;
    private long advertiseMessages;
    private long handoverMessages;
    private long placementMessages;

    public static ControlOverheadMonitor getInstance() {
        return INSTANCE;
    }

    public void reset() {
        queryMessages = 0;
        advertiseMessages = 0;
        handoverMessages = 0;
        placementMessages = 0;
    }

    public void recordQuery() {
        queryMessages++;
    }

    public void recordAdvertise() {
        advertiseMessages++;
    }

    public void recordHandover() {
        handoverMessages++;
    }

    public void recordPlacement() {
        placementMessages++;
    }

    public long totalMessages() {
        return queryMessages + advertiseMessages + handoverMessages + placementMessages;
    }

    public double normalizedOverhead(int numNodes) {
        if (numNodes <= 0) {
            return 0.0;
        }
        double bytes = queryMessages * 256.0
                + advertiseMessages * 512.0
                + handoverMessages * 384.0
                + placementMessages * 384.0;
        return bytes / numNodes;
    }

    public long getQueryMessages() {
        return queryMessages;
    }

    public long getAdvertiseMessages() {
        return advertiseMessages;
    }

    public long getHandoverMessages() {
        return handoverMessages;
    }

    public long getPlacementMessages() {
        return placementMessages;
    }
}
