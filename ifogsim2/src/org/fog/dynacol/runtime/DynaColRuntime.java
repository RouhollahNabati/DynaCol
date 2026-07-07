package org.fog.dynacol.runtime;

import org.fog.dynacol.DynaColFeatureFlags;
import org.fog.dynacol.colony.ColonyFormationProtocol;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.placement.PlacementPolicy;
import org.fog.dynacol.placement.PlacementStrategy;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class DynaColRuntime {

    private final Map<Integer, FogNodeState> nodeStates;
    private final PlacementStrategy placementStrategy;
    private final PlacementPolicy placementPolicy;
    private final DynaColFeatureFlags featureFlags;
    private ColonyFormationProtocol formationProtocol;
    private DynaColPlacementManager placementManager;
    private List<org.fog.entities.FogDevice> allDevices;
    private double sensorPeriodMs = 5.0;
    private List<org.fog.entities.FogDevice> lateArrivalOrder = new ArrayList<>();
    private boolean incrementalArrivalMode;

    public DynaColRuntime(Map<Integer, FogNodeState> nodeStates,
                          PlacementStrategy placementStrategy,
                          PlacementPolicy placementPolicy,
                          DynaColFeatureFlags featureFlags) {
        this.nodeStates = nodeStates;
        this.placementStrategy = placementStrategy;
        this.placementPolicy = placementPolicy;
        this.featureFlags = featureFlags != null ? featureFlags : DynaColFeatureFlags.full();
    }

    public Map<Integer, FogNodeState> getNodeStates() {
        return nodeStates;
    }

    public PlacementStrategy getPlacementStrategy() {
        return placementStrategy;
    }

    public PlacementPolicy getPlacementPolicy() {
        return placementPolicy;
    }

    public DynaColFeatureFlags getFeatureFlags() {
        return featureFlags;
    }

    public ColonyFormationProtocol getFormationProtocol() {
        return formationProtocol;
    }

    public void setFormationProtocol(ColonyFormationProtocol formationProtocol) {
        this.formationProtocol = formationProtocol;
    }

    public DynaColPlacementManager getPlacementManager() {
        return placementManager;
    }

    public void setPlacementManager(DynaColPlacementManager placementManager) {
        this.placementManager = placementManager;
    }

    public List<org.fog.entities.FogDevice> getAllDevices() {
        return allDevices;
    }

    public void setAllDevices(List<org.fog.entities.FogDevice> allDevices) {
        this.allDevices = allDevices;
    }

    public double getSensorPeriodMs() {
        return sensorPeriodMs;
    }

    public void setSensorPeriodMs(double sensorPeriodMs) {
        if (sensorPeriodMs > 0) {
            this.sensorPeriodMs = sensorPeriodMs;
        }
    }

    public List<org.fog.entities.FogDevice> getLateArrivalOrder() {
        return lateArrivalOrder;
    }

    public void setLateArrivalOrder(List<org.fog.entities.FogDevice> lateArrivalOrder) {
        this.lateArrivalOrder = lateArrivalOrder != null ? lateArrivalOrder : new ArrayList<>();
    }

    public boolean isIncrementalArrivalMode() {
        return incrementalArrivalMode;
    }

    public void setIncrementalArrivalMode(boolean incrementalArrivalMode) {
        this.incrementalArrivalMode = incrementalArrivalMode;
    }
}
