package org.fog.dynacol.baseline.tavousi;

import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ResourceVector;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.placement.PlacementCandidateUtil;
import org.fog.dynacol.placement.PlacementStrategy;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Literature-inspired fuzzy IoT placement (Tavousi et al., Cluster Computing 2022).
 */
public class FuzzyTavousiPlacement implements PlacementStrategy {

    private final Map<Integer, FogDevice> deviceIndex;
    private final ResourceVector maxResources;

    public FuzzyTavousiPlacement(List<FogDevice> devices) {
        this.deviceIndex = FogTopologyUtil.indexById(devices);
        this.maxResources = FogTopologyUtil.maxResources(devices);
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
                .max(Comparator.comparingDouble(entry -> fuzzyScore(source, request, entry)))
                .map(entry -> {
                    ControlOverheadMonitor.getInstance().recordPlacement();
                    entry.setAvailable(entry.getAvailable().subtract(request.getDemand()));
                    return entry.getFogDeviceId();
                });
    }

    private double fuzzyScore(FogDevice source, ServiceRequest request, ColonyResourceEntry entry) {
        FogDevice target = deviceIndex.get(entry.getFogDeviceId());
        double latency = source == null
                ? entry.getRttToFcmMs()
                : FogTopologyUtil.estimateRttMs(source, target, deviceIndex);
        ResourceVector avail = entry.getAvailable();
        ResourceVector norm = ResourceVector.normalize(avail, maxResources);

        double latencyLow = triMembership(latency, 0, 0, 80);
        double latencyMed = triMembership(latency, 40, 80, 160);
        double latencyHigh = triMembership(latency, 120, 200, 400);
        double latScore = Math.max(latencyLow, latencyMed * 0.6) - latencyHigh * 0.3;

        double cpuLow = triMembership(norm.getCpu(), 0, 0, 0.35);
        double cpuMed = triMembership(norm.getCpu(), 0.2, 0.5, 0.8);
        double cpuHigh = triMembership(norm.getCpu(), 0.6, 0.85, 1.0);
        double cpuScore = cpuHigh + cpuMed * 0.5 - cpuLow * 0.2;

        double ramLow = triMembership(norm.getRam(), 0, 0, 0.35);
        double ramMed = triMembership(norm.getRam(), 0.2, 0.5, 0.8);
        double ramHigh = triMembership(norm.getRam(), 0.6, 0.85, 1.0);
        double ramScore = ramHigh + ramMed * 0.4 - ramLow * 0.2;

        double slaPenalty = latency > request.getDeadlineMs() ? -0.5 : 0.1;
        return 0.45 * latScore + 0.30 * cpuScore + 0.25 * ramScore + slaPenalty;
    }

    /** Triangular membership function. */
    private static double triMembership(double x, double a, double b, double c) {
        if (x <= a || x >= c) {
            return 0.0;
        }
        if (x <= b) {
            return (b - a) <= 0 ? 0.0 : (x - a) / (b - a);
        }
        return (c - b) <= 0 ? 0.0 : (c - x) / (c - b);
    }
}
