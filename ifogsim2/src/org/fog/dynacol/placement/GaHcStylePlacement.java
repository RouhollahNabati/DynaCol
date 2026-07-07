package org.fog.dynacol.placement;

import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Lightweight GA+HC adapter: sample feasible CRT candidates and pick the best objective score.
 */
public class GaHcStylePlacement implements PlacementStrategy {

    private static final int SAMPLE_SIZE = 5;

    private final Map<Integer, FogDevice> deviceIndex;
    private final ObjectiveFunction objectiveFunction;
    private final Random random;

    public GaHcStylePlacement(List<FogDevice> devices, Random random) {
        this.deviceIndex = FogTopologyUtil.indexById(devices);
        this.objectiveFunction = new ObjectiveFunction(devices);
        this.random = random;
    }

    @Override
    public Optional<Integer> placeService(ServiceRequest request, FogNodeState localFcmState) {
        if (localFcmState == null) {
            return Optional.empty();
        }
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
        Collections.shuffle(feasible, random);
        int k = Math.min(SAMPLE_SIZE, feasible.size());
        return feasible.subList(0, k).stream()
                .peek(e -> ControlOverheadMonitor.getInstance().recordPlacement())
                .min(Comparator.comparingDouble(entry -> objectiveFunction.score(request, entry)))
                .map(entry -> {
                    entry.setAvailable(entry.getAvailable().subtract(request.getDemand()));
                    return entry.getFogDeviceId();
                });
    }
}
