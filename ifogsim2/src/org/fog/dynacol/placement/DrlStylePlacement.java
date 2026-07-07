package org.fog.dynacol.placement;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Tabular epsilon-greedy adapter inspired by DRL placement (no offline training).
 */
public class DrlStylePlacement implements PlacementStrategy {

    private static final double EPSILON = 0.15;

    private final Map<Integer, FogDevice> deviceIndex;
    private final AttractivenessModel attractivenessModel;
    private final Random random;

    public DrlStylePlacement(List<FogDevice> devices, Random random) {
        this.deviceIndex = FogTopologyUtil.indexById(devices);
        this.attractivenessModel = new AttractivenessModel();
        this.random = random;
    }

    @Override
    public Optional<Integer> placeService(ServiceRequest request, FogNodeState localFcmState) {
        if (localFcmState == null) {
            return Optional.empty();
        }
        ControlOverheadMonitor.getInstance().recordPlacement();
        attractivenessModel.applyTemporalDecay(localFcmState.getCrt(), localFcmState.getGrt());

        List<ColonyResourceEntry> feasible = PlacementCandidateUtil.fogCandidates(
                        localFcmState.getCrt().asList(), deviceIndex, request).stream()
                .filter(entry -> entry.getAvailable().canFit(request.getDemand()))
                .collect(Collectors.toList());
        if (feasible.isEmpty()) {
            return Optional.empty();
        }

        ColonyResourceEntry chosen;
        if (random.nextDouble() < EPSILON) {
            chosen = feasible.get(random.nextInt(feasible.size()));
            ControlOverheadMonitor.getInstance().recordQuery();
        } else {
            chosen = feasible.stream()
                    .max(Comparator.comparingDouble(ColonyResourceEntry::getAttractiveness))
                    .orElse(feasible.get(0));
        }

        FogDevice target = deviceIndex.get(chosen.getFogDeviceId());
        double reward = attractivenessModel.reward(request, target, deviceIndex);
        attractivenessModel.updateColonyEntry(chosen, request, reward);
        chosen.setAvailable(chosen.getAvailable().subtract(request.getDemand()));
        return Optional.of(chosen.getFogDeviceId());
    }
}
