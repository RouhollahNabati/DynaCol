package org.fog.dynacol.placement;

import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public class RandomFeasiblePlacement implements PlacementStrategy {

    private final Map<Integer, FogDevice> deviceIndex;
    private final Random random;

    public RandomFeasiblePlacement(List<FogDevice> devices, Random random) {
        this.deviceIndex = FogTopologyUtil.indexById(devices);
        this.random = random;
    }

    @Override
    public Optional<Integer> placeService(ServiceRequest request, FogNodeState localFcmState) {
        if (localFcmState == null) {
            return Optional.empty();
        }
        ControlOverheadMonitor.getInstance().recordQuery();
        List<ColonyResourceEntry> candidates = PlacementCandidateUtil.fogCandidates(
                localFcmState.getCrt().asList(), deviceIndex, request);
        List<ColonyResourceEntry> feasible = new ArrayList<>();
        for (ColonyResourceEntry entry : candidates) {
            if (entry.getAvailable().canFit(request.getDemand())) {
                feasible.add(entry);
            }
        }
        if (feasible.isEmpty()) {
            return Optional.empty();
        }
        ColonyResourceEntry chosen = feasible.get(random.nextInt(feasible.size()));
        ControlOverheadMonitor.getInstance().recordPlacement();
        chosen.setAvailable(chosen.getAvailable().subtract(request.getDemand()));
        return Optional.of(chosen.getFogDeviceId());
    }
}
