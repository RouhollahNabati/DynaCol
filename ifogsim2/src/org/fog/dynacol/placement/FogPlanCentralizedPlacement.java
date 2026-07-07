package org.fog.dynacol.placement;

import org.fog.dynacol.baseline.fogplan.FogPlanMinCostEngine;
import org.fog.dynacol.baseline.fogplan.FogPlanPlacementOps;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FogPlan Min-Cost with centralized FSC polling overhead (advertise per CRT entry),
 * per FogPlan paper's centralized Fog Service Controller model.
 */
public class FogPlanCentralizedPlacement implements PlacementStrategy, FogPlanPlacementOps {

    private final FogPlanMinCostEngine engine;
    private final Map<Integer, FogDevice> deviceIndex;

    public FogPlanCentralizedPlacement(List<FogDevice> devices) {
        this.engine = new FogPlanMinCostEngine(devices);
        this.deviceIndex = FogTopologyUtil.indexById(devices);
    }

    @Override
    public Optional<Integer> placeService(ServiceRequest request, FogNodeState localFcmState) {
        if (localFcmState == null) {
            return Optional.empty();
        }
        List<ColonyResourceEntry> candidates = PlacementCandidateUtil.fogCandidates(
                localFcmState.getCrt().asList(), deviceIndex, request);
        for (int i = 0; i < candidates.size(); i++) {
            ControlOverheadMonitor.getInstance().recordAdvertise();
        }
        ControlOverheadMonitor.getInstance().recordQuery();
        registerTraffic(request);
        int serviceIndex = org.fog.dynacol.baseline.fogplan.FogPlanServiceIndex.indexOf(request.getModuleName());
        optimizeService(serviceIndex);
        return resolveHost(request, localFcmState);
    }

    @Override
    public void registerTraffic(ServiceRequest request) {
        engine.registerTraffic(request);
    }

    @Override
    public void optimizeService(int serviceIndex) {
        engine.optimizeService(serviceIndex);
    }

    @Override
    public void runPeriodicOptimization() {
        int fogHosts = (int) deviceIndex.values().stream()
                .filter(PlacementCandidateUtil::isFogHost)
                .count();
        for (int i = 0; i < fogHosts; i++) {
            ControlOverheadMonitor.getInstance().recordAdvertise();
        }
        engine.runPeriodicOptimization();
    }

    @Override
    public Optional<Integer> resolveHost(ServiceRequest request, FogNodeState localFcmState) {
        return engine.resolveHost(request, localFcmState);
    }
}
