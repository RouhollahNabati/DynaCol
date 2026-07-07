package org.fog.dynacol.placement;

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

public class GreedyNearestPlacement implements PlacementStrategy {

    private final Map<Integer, FogDevice> deviceIndex;

    public GreedyNearestPlacement(List<FogDevice> devices) {
        this.deviceIndex = FogTopologyUtil.indexById(devices);
    }

    @Override
    public Optional<Integer> placeService(ServiceRequest request, FogNodeState localFcmState) {
        if (localFcmState == null) {
            return Optional.empty();
        }
        FogDevice source = deviceIndex.get(request.getSourceDeviceId());
        List<ColonyResourceEntry> candidates = PlacementCandidateUtil.fogCandidates(
                localFcmState.getCrt().asList(), deviceIndex, request);
        ControlOverheadMonitor.getInstance().recordQuery();
        return candidates.stream()
                .filter(entry -> entry.getAvailable().canFit(request.getDemand()))
                .min(Comparator.comparingDouble(entry -> distance(source, entry)))
                .map(entry -> {
                    ControlOverheadMonitor.getInstance().recordPlacement();
                    entry.setAvailable(entry.getAvailable().subtract(request.getDemand()));
                    return entry.getFogDeviceId();
                });
    }

    private double distance(FogDevice source, ColonyResourceEntry entry) {
        if (source == null) {
            return entry.getRttToFcmMs();
        }
        FogDevice target = deviceIndex.get(entry.getFogDeviceId());
        return FogTopologyUtil.estimateRttMs(source, target, deviceIndex);
    }
}
