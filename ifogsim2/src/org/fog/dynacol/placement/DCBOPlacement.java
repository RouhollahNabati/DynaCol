package org.fog.dynacol.placement;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.DynaColFeatureFlags;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.ColonySummary;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.table.ColonyResourceTable;
import org.fog.dynacol.table.GlobalResourceTable;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Algorithm 3: DCBO_PlaceService with hierarchical L1/L2/L3 depth and top-K CRT/GRT pruning.
 */
public class DCBOPlacement implements PlacementStrategy {

    private final Map<Integer, FogNodeState> nodeStates;
    private final Map<Integer, FogDevice> deviceIndex;
    private final List<FogDevice> allDevices;
    private final ObjectiveFunction objectiveFunction;
    private final AttractivenessModel attractivenessModel;
    private final DynaColFeatureFlags flags;

    public DCBOPlacement(Map<Integer, FogNodeState> nodeStates, List<FogDevice> allDevices) {
        this(nodeStates, allDevices, DynaColFeatureFlags.full());
    }

    public DCBOPlacement(Map<Integer, FogNodeState> nodeStates,
                         List<FogDevice> allDevices,
                         DynaColFeatureFlags flags) {
        this.nodeStates = nodeStates;
        this.allDevices = allDevices;
        this.deviceIndex = FogTopologyUtil.indexById(allDevices);
        this.objectiveFunction = new ObjectiveFunction(allDevices);
        this.flags = flags != null ? flags : DynaColFeatureFlags.full();
        this.attractivenessModel = new AttractivenessModel(this.flags);
    }

    @Override
    public Optional<Integer> placeService(ServiceRequest request, FogNodeState localFcmState) {
        return reconcileService(request, localFcmState, null, PlacementDepth.L3_GLOBAL);
    }

    /**
     * Hierarchical placement: L1 sticky fast-path, L2 pruned colony search, L3 cloud fallback.
     *
     * @param currentHostId null on initial placement (skips L1 sticky)
     */
    public Optional<Integer> reconcileService(ServiceRequest request,
                                              FogNodeState localFcmState,
                                              Integer currentHostId,
                                              PlacementDepth maxDepth) {
        if (localFcmState == null) {
            return Optional.empty();
        }

        if (maxDepth.ordinal() >= PlacementDepth.L1_FAST.ordinal() && currentHostId != null) {
            Optional<Integer> sticky = tryFastPath(request, localFcmState, currentHostId);
            if (sticky.isPresent()) {
                return sticky;
            }
        }

        if (maxDepth.ordinal() >= PlacementDepth.L2_COLONY.ordinal()) {
            ControlOverheadMonitor.getInstance().recordQuery();
            if (flags.isLearningEnabled()) {
                attractivenessModel.applyTemporalDecay(localFcmState.getCrt(), localFcmState.getGrt());
            }

            if (flags.isCrtEnabled()) {
                Optional<Integer> local = placeLocalPruned(request, localFcmState.getCrt(), localFcmState.getGrt());
                if (local.isPresent()) {
                    return finalizePlacement(request, localFcmState.getCrt(), local.get());
                }
            }

            if (flags.isGrtEnabled()) {
                Optional<Integer> remote = placeRemotePruned(request, localFcmState.getGrt());
                if (remote.isPresent()) {
                    return Optional.of(remote.get());
                }
            }

            if (currentHostId != null && hostStillFeasible(request, localFcmState, currentHostId)) {
                return Optional.of(currentHostId);
            }
        }

        if (maxDepth == PlacementDepth.L3_GLOBAL) {
            return placeCloud(request);
        }

        return currentHostId != null ? Optional.of(currentHostId) : Optional.empty();
    }

    /** L1: no control messages if current host remains acceptable. */
    private Optional<Integer> tryFastPath(ServiceRequest request,
                                          FogNodeState localFcmState,
                                          int currentHostId) {
        if (!hostStillFeasible(request, localFcmState, currentHostId)) {
            return Optional.empty();
        }
        double latency = estimateLatencyMs(request, currentHostId);
        double deadline = request.getDeadlineMs();
        if (latency <= deadline * DynaColConfig.SLA_STICKY_LOW_RATIO) {
            return Optional.of(currentHostId);
        }
        if (latency > deadline * DynaColConfig.SLA_ESCALATE_HIGH_RATIO) {
            return Optional.empty();
        }

        ColonyResourceTable crt = localFcmState.getCrt();
        if (crt == null) {
            return Optional.of(currentHostId);
        }
        double currentScore = scoreEntry(request, crt.get(currentHostId));
        double bestLocal = bestLocalScore(request, crt, localFcmState.getGrt());
        if (bestLocal == Double.MAX_VALUE
                || currentScore <= bestLocal * (1.0 + DynaColConfig.STICKY_SCORE_MARGIN)) {
            return Optional.of(currentHostId);
        }
        return Optional.empty();
    }

    private boolean hostStillFeasible(ServiceRequest request, FogNodeState localFcmState, int hostId) {
        if (localFcmState.getCrt() == null) {
            return false;
        }
        ColonyResourceEntry entry = localFcmState.getCrt().get(hostId);
        return entry != null && feasible(request, entry);
    }

    private double estimateLatencyMs(ServiceRequest request, int hostId) {
        FogDevice source = deviceIndex.get(request.getSourceDeviceId());
        FogDevice target = deviceIndex.get(hostId);
        if (source == null || target == null) {
            return Double.MAX_VALUE;
        }
        return FogTopologyUtil.estimateRttMs(source, target, deviceIndex);
    }

    private double bestLocalScore(ServiceRequest request,
                                  ColonyResourceTable crt,
                                  GlobalResourceTable grt) {
        List<ScoredCandidate> ranked = rankLocalCandidates(request, crt, grt);
        return ranked.isEmpty() ? Double.MAX_VALUE : ranked.get(0).score;
    }

    private double scoreEntry(ServiceRequest request, ColonyResourceEntry entry) {
        if (entry == null || !feasible(request, entry)) {
            return Double.MAX_VALUE;
        }
        double score = objectiveFunction.score(request, entry);
        if (flags.isLearningEnabled()) {
            score -= DynaColConfig.LAMBDA_ATTRACTIVENESS * entry.getAttractiveness();
        }
        return score;
    }

    private Optional<Integer> placeLocalPruned(ServiceRequest request,
                                               ColonyResourceTable crt,
                                               GlobalResourceTable grt) {
        List<ScoredCandidate> ranked = rankLocalCandidates(request, crt, grt);
        List<ScoredCandidate> pruned = PlacementCandidateUtil.topKByScore(
                ranked, DynaColConfig.TOP_K_CRT_CANDIDATES, c -> c.score);

        for (ScoredCandidate c : pruned) {
            ColonyResourceEntry entry = crt.get(c.deviceId);
            if (entry == null || !feasible(request, entry)) {
                continue;
            }
            return Optional.of(c.deviceId);
        }
        return Optional.empty();
    }

    private List<ScoredCandidate> rankLocalCandidates(ServiceRequest request,
                                                    ColonyResourceTable crt,
                                                    GlobalResourceTable grt) {
        List<ColonyResourceEntry> candidates = PlacementCandidateUtil.fogCandidates(
                crt.asList(), deviceIndex, request);
        List<ScoredCandidate> ranked = new ArrayList<>();
        for (ColonyResourceEntry entry : candidates) {
            if (!feasible(request, entry)) {
                continue;
            }
            ranked.add(new ScoredCandidate(entry.getFogDeviceId(), scoreEntry(request, entry)));
        }
        ranked.sort(Comparator.comparingDouble(c -> c.score));
        return ranked;
    }

    private Optional<Integer> finalizePlacement(ServiceRequest request,
                                                ColonyResourceTable crt,
                                                int deviceId) {
        ColonyResourceEntry entry = crt.get(deviceId);
        if (entry == null || !feasible(request, entry)) {
            return Optional.empty();
        }
        ControlOverheadMonitor.getInstance().recordPlacement();
        if (flags.isLearningEnabled()) {
            double reward = attractivenessModel.reward(request, deviceIndex.get(deviceId), deviceIndex);
            attractivenessModel.updateColonyEntry(entry, request, reward);
        }
        reserveResources(entry, request);
        return Optional.of(deviceId);
    }

    private Optional<Integer> placeRemotePruned(ServiceRequest request, GlobalResourceTable grt) {
        List<ScoredColony> ranked = new ArrayList<>();
        for (ColonySummary summary : grt.asList()) {
            if (!colonyMayFit(request, summary)) {
                continue;
            }
            double score = objectiveFunction.score(request, summary);
            if (flags.isLearningEnabled()) {
                score -= DynaColConfig.LAMBDA_COLONY * summary.getAttractiveness();
            }
            ranked.add(new ScoredColony(summary, score));
        }
        ranked.sort(Comparator.comparingDouble(c -> c.score));
        List<ScoredColony> pruned = PlacementCandidateUtil.topKByScore(
                ranked, DynaColConfig.TOP_K_GRT_COLONIES, c -> c.score);

        for (ScoredColony candidate : pruned) {
            ColonySummary summary = candidate.summary;
            FogNodeState remoteFcm = nodeStates.get(summary.getFcmDeviceId());
            if (remoteFcm == null || remoteFcm.getCrt() == null) {
                continue;
            }
            Optional<Integer> delegated = placeLocalPruned(request, remoteFcm.getCrt(), grt);
            if (delegated.isPresent()) {
                ControlOverheadMonitor.getInstance().recordPlacement();
                if (flags.isLearningEnabled()) {
                    double reward = attractivenessModel.reward(
                            request, deviceIndex.get(delegated.get()), deviceIndex);
                    attractivenessModel.updateColonySummary(summary, request, reward);
                }
                ColonyResourceEntry entry = remoteFcm.getCrt().get(delegated.get());
                if (entry != null) {
                    reserveResources(entry, request);
                }
                return delegated;
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> placeCloud(ServiceRequest request) {
        FogDevice cloud = allDevices.stream()
                .filter(d -> d.getParentId() == -1)
                .findFirst()
                .orElse(null);
        if (cloud == null) {
            return Optional.empty();
        }
        ControlOverheadMonitor.getInstance().recordPlacement();
        return Optional.of(cloud.getId());
    }

    private boolean feasible(ServiceRequest request, ColonyResourceEntry entry) {
        return entry.getAvailable().canFit(request.getDemand());
    }

    private boolean colonyMayFit(ServiceRequest request, ColonySummary summary) {
        return summary.getAggregateAvailable().canFit(request.getDemand());
    }

    private void reserveResources(ColonyResourceEntry entry, ServiceRequest request) {
        entry.setAvailable(entry.getAvailable().subtract(request.getDemand()));
    }

    private static class ScoredCandidate {
        final int deviceId;
        final double score;

        ScoredCandidate(int deviceId, double score) {
            this.deviceId = deviceId;
            this.score = score;
        }
    }

    private static class ScoredColony {
        final ColonySummary summary;
        final double score;

        ScoredColony(ColonySummary summary, double score) {
            this.summary = summary;
            this.score = score;
        }
    }
}
