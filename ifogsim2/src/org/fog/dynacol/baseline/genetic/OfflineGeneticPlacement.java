package org.fog.dynacol.baseline.genetic;

import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ResourceVector;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.placement.ObjectiveFunction;
import org.fog.dynacol.placement.PlacementCandidateUtil;
import org.fog.dynacol.placement.PlacementStrategy;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Offline generational GA on static geography overlay; runtime uses evolved module preferences.
 */
public class OfflineGeneticPlacement implements PlacementStrategy {

    private final Map<Integer, FogDevice> deviceIndex;
    private final ObjectiveFunction objectiveFunction;
    private final Map<String, Integer> preferredHosts;

    public OfflineGeneticPlacement(List<FogDevice> devices, Random random) {
        this.deviceIndex = FogTopologyUtil.indexById(devices);
        this.objectiveFunction = new ObjectiveFunction(devices);
        List<ColonyResourceEntry> crtEntries = new java.util.ArrayList<>();
        for (FogDevice device : devices) {
            if (PlacementCandidateUtil.isFogHost(device)) {
                crtEntries.add(new ColonyResourceEntry(
                        device.getId(),
                        ResourceVector.availableFromFogDevice(device),
                        0.0));
            }
        }
        this.preferredHosts = new OfflineGeneticEngine(devices, crtEntries, random).evolvePreferredHosts();
    }

    @Override
    public Optional<Integer> placeService(ServiceRequest request, FogNodeState localFcmState) {
        if (localFcmState == null) {
            return Optional.empty();
        }
        List<ColonyResourceEntry> candidates = PlacementCandidateUtil.fogCandidates(
                localFcmState.getCrt().asList(), deviceIndex, request);
        List<ColonyResourceEntry> feasible = candidates.stream()
                .filter(entry -> entry.getAvailable().canFit(request.getDemand()))
                .toList();
        if (feasible.isEmpty()) {
            return Optional.empty();
        }
        Integer preferred = preferredHosts.get(request.getModuleName());
        if (preferred != null) {
            for (ColonyResourceEntry entry : feasible) {
                if (entry.getFogDeviceId() == preferred) {
                    ControlOverheadMonitor.getInstance().recordPlacement();
                    entry.setAvailable(entry.getAvailable().subtract(request.getDemand()));
                    return Optional.of(preferred);
                }
            }
        }
        return feasible.stream()
                .peek(e -> ControlOverheadMonitor.getInstance().recordPlacement())
                .min(Comparator.comparingDouble(entry -> objectiveFunction.score(request, entry)))
                .map(entry -> {
                    entry.setAvailable(entry.getAvailable().subtract(request.getDemand()));
                    return entry.getFogDeviceId();
                });
    }
}
