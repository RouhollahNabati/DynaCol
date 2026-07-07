package org.fog.dynacol.baseline.dogani;

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
 * Two-tier multi-objective placement inspired by Dogani et al. (Cluster Computing 2024).
 */
public class TwoTierDoganiPlacement implements PlacementStrategy {

    private static final double ALPHA_LATENCY = 0.50;
    private static final double BETA_COST = 0.25;
    private static final double GAMMA_UTIL = 0.20;
    private static final double DELTA_TIER = 0.05;
    private static final int LOCAL_CANDIDATE_K = 5;
    private static final long TIER2_MIPS_THRESHOLD = 6000L;

    private final Map<Integer, FogDevice> deviceIndex;
    private final ResourceVector maxResources;

    public TwoTierDoganiPlacement(List<FogDevice> devices) {
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
        List<ColonyResourceEntry> feasible = candidates.stream()
                .filter(entry -> entry.getAvailable().canFit(request.getDemand()))
                .toList();
        if (feasible.isEmpty()) {
            return Optional.empty();
        }
        ControlOverheadMonitor.getInstance().recordQuery();
        boolean latencySensitive = isLatencySensitive(request);
        List<ColonyResourceEntry> localPool = PlacementCandidateUtil.topKByScore(
                feasible,
                LOCAL_CANDIDATE_K,
                entry -> rttMs(source, entry));
        return localPool.stream()
                .min(Comparator
                        .comparingDouble((ColonyResourceEntry entry) -> rttMs(source, entry))
                        .thenComparingDouble(entry -> multiObjectiveScore(
                                source, request, entry, latencySensitive)))
                .map(entry -> {
                    ControlOverheadMonitor.getInstance().recordPlacement();
                    entry.setAvailable(entry.getAvailable().subtract(request.getDemand()));
                    return entry.getFogDeviceId();
                });
    }

    private boolean isLatencySensitive(ServiceRequest request) {
        String module = request.getModuleName();
        return module != null && !"object_tracker".equals(module);
    }

    private double rttMs(FogDevice source, ColonyResourceEntry entry) {
        if (source == null) {
            return entry.getRttToFcmMs();
        }
        FogDevice target = deviceIndex.get(entry.getFogDeviceId());
        return FogTopologyUtil.estimateRttMs(source, target, deviceIndex);
    }

    private double multiObjectiveScore(FogDevice source,
                                       ServiceRequest request,
                                       ColonyResourceEntry entry,
                                       boolean tier1Preferred) {
        FogDevice target = deviceIndex.get(entry.getFogDeviceId());
        double latency = rttMs(source, entry);
        double latNorm = Math.min(1.0, latency / 200.0);

        double rate = target != null ? target.getRatePerMips() : 0.0;
        double costNorm = Math.min(1.0, rate / 0.01);

        ResourceVector used = ResourceVector.fromFogDevice(target);
        ResourceVector normUsed = ResourceVector.normalize(used, maxResources);
        double utilNorm = (normUsed.getCpu() + normUsed.getRam()) / 2.0;

        double slaPenalty = latency > request.getDeadlineMs() ? 1.0 : 0.0;
        double tierPenalty = tierMismatchPenalty(target, tier1Preferred);
        return ALPHA_LATENCY * latNorm
                + BETA_COST * costNorm
                + GAMMA_UTIL * utilNorm
                + DELTA_TIER * tierPenalty
                + slaPenalty;
    }

    /** Soft two-tier preference (hard filtering breaks cross-area routing in iFogSim). */
    private double tierMismatchPenalty(FogDevice target, boolean tier1Preferred) {
        if (target == null) {
            return 1.0;
        }
        boolean tier2 = target.getHost().getTotalMips() >= TIER2_MIPS_THRESHOLD;
        if (tier1Preferred) {
            return tier2 ? 1.0 : 0.0;
        }
        return tier2 ? 0.0 : 1.0;
    }
}
