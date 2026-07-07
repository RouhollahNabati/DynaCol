package org.fog.dynacol.model;

import org.fog.dynacol.table.ColonyResourceTable;
import org.fog.dynacol.table.GlobalResourceTable;

/**
 * Per-node runtime state for cold-start colony bootstrap.
 */
public class FogNodeState {

    private final int fogDeviceId;
    private NodeMembershipState state = NodeMembershipState.UNASSIGNED;
    private Long colonyId;
    private Integer fcmDeviceId;
    private double nearestCloudRttMs = 70.0;
    private ColonyResourceTable crt = new ColonyResourceTable();
    private GlobalResourceTable grt = new GlobalResourceTable();
    private double lastHandoverTime = -Double.MAX_VALUE;
    /** Simulation time (ms) of last FCM advertisement; -1 if never advertised. */
    private double lastAdvertiseTimeMs = -1.0;

    public FogNodeState(int fogDeviceId) {
        this.fogDeviceId = fogDeviceId;
    }

    public int getFogDeviceId() {
        return fogDeviceId;
    }

    public NodeMembershipState getState() {
        return state;
    }

    public void setState(NodeMembershipState state) {
        this.state = state;
    }

    public Long getColonyId() {
        return colonyId;
    }

    public void setColonyId(Long colonyId) {
        this.colonyId = colonyId;
    }

    public Integer getFcmDeviceId() {
        return fcmDeviceId;
    }

    public void setFcmDeviceId(Integer fcmDeviceId) {
        this.fcmDeviceId = fcmDeviceId;
    }

    public double getNearestCloudRttMs() {
        return nearestCloudRttMs;
    }

    public void setNearestCloudRttMs(double nearestCloudRttMs) {
        this.nearestCloudRttMs = nearestCloudRttMs;
    }

    public ColonyResourceTable getCrt() {
        return crt;
    }

    public void setCrt(ColonyResourceTable crt) {
        this.crt = crt;
    }

    public GlobalResourceTable getGrt() {
        return grt;
    }

    public void setGrt(GlobalResourceTable grt) {
        this.grt = grt;
    }

    public double getLastHandoverTime() {
        return lastHandoverTime;
    }

    public void setLastHandoverTime(double lastHandoverTime) {
        this.lastHandoverTime = lastHandoverTime;
    }

    public double getLastAdvertiseTimeMs() {
        return lastAdvertiseTimeMs;
    }

    public void setLastAdvertiseTimeMs(double lastAdvertiseTimeMs) {
        this.lastAdvertiseTimeMs = lastAdvertiseTimeMs;
    }

    public boolean isFcm() {
        return state == NodeMembershipState.FCM;
    }
}
