package org.fog.dynacol.colony;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.DynaColFeatureFlags;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.NodeMembershipState;
import org.fog.dynacol.model.ResourceVector;
import org.fog.dynacol.table.ColonyResourceTable;
import org.fog.dynacol.table.GlobalResourceTable;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Algorithm 1: Create_And_Add_FogNode — event-driven colony bootstrap with pruned queries.
 */
public class ColonyFormationProtocol {

    private final Map<Integer, FogNodeState> nodeStates;
    private final Map<Integer, FogDevice> deviceIndex;
    private final List<FogDevice> allDevices;
    private final Random random;
    private final DynaColFeatureFlags flags;
    private final ManagerHandoverService handoverService;
    private final ColonyMergeService mergeService;
    private final PotencyCalculator potencyCalculator;
    private final ResourceVector maxResources;
    private final Set<Integer> dirtyFcmIds = new HashSet<>();

    private boolean bootstrapPhase = true;
    private int nodesSinceMergeCheck;

    public ColonyFormationProtocol(Map<Integer, FogNodeState> nodeStates,
                                   List<FogDevice> allDevices,
                                   Random random) {
        this(nodeStates, allDevices, random, DynaColFeatureFlags.full());
    }

    public ColonyFormationProtocol(Map<Integer, FogNodeState> nodeStates,
                                   List<FogDevice> allDevices,
                                   Random random,
                                   DynaColFeatureFlags flags) {
        this.nodeStates = nodeStates;
        this.allDevices = allDevices;
        this.deviceIndex = FogTopologyUtil.indexById(allDevices);
        this.random = random;
        this.flags = flags != null ? flags : DynaColFeatureFlags.full();
        this.handoverService = new ManagerHandoverService(nodeStates, allDevices, this.flags);
        this.mergeService = new ColonyMergeService(nodeStates, allDevices, this, this.flags);
        this.potencyCalculator = new PotencyCalculator(allDevices);
        this.maxResources = FogTopologyUtil.maxResources(allDevices);
    }

    /**
     * Called once after pre-simulation cold-start loop; batches deferred GRT updates.
     */
    public void finalizeColdStartBootstrap(double currentTimeMs) {
        if (flags.isHandoverEnabled()) {
            mergeService.consolidateAfterBootstrap(currentTimeMs);
        }
        bootstrapPhase = false;
        refreshDirtyGrt(currentTimeMs, true);
    }

    public void createAndAddFogNode(FogDevice node, double currentTimeMs) {
        FogNodeState state = nodeStates.computeIfAbsent(node.getId(), FogNodeState::new);
        if (state.getState() != NodeMembershipState.UNASSIGNED) {
            return;
        }

        state.setNearestCloudRttMs(FogTopologyUtil.nearestCloudRtt(node, allDevices, deviceIndex));

        ControlOverheadMonitor.getInstance().recordQuery();
        List<ColonyAdvertisement> responses = queryColoniesPruned(node, currentTimeMs);
        ColonyAdvertisement best = selectBestResponse(responses);

        boolean selfElected = false;
        if (best == null) {
            selfElectAsFcm(node, state, currentTimeMs);
            selfElected = true;
        } else if (best.rttMs <= DynaColConfig.maxColonyRttMs()) {
            if (flags.isHandoverEnabled()) {
                handoverService.joinOrNegotiate(node, state, best, currentTimeMs);
            } else {
                joinAsMemberOnly(node, state, best, currentTimeMs);
            }
        } else {
            selfElectAsFcm(node, state, currentTimeMs);
            selfElected = true;
        }

        maybeAttemptMerges(currentTimeMs, selfElected);
    }

    private void maybeAttemptMerges(double currentTimeMs, boolean selfElected) {
        if (!flags.isHandoverEnabled()) {
            return;
        }
        nodesSinceMergeCheck++;
        if (selfElected || nodesSinceMergeCheck >= DynaColConfig.MERGE_CHECK_EVERY_N_NODES) {
            nodesSinceMergeCheck = 0;
            mergeService.attemptMerges(currentTimeMs);
        }
    }

    private void joinAsMemberOnly(FogDevice candidate, FogNodeState candidateState,
                                  ColonyAdvertisement resp, double currentTimeMs) {
        candidateState.setState(NodeMembershipState.MEMBER);
        candidateState.setColonyId(resp.colonyId);
        candidateState.setFcmDeviceId(resp.fcmDeviceId);
        FogNodeState fcmState = nodeStates.get(resp.fcmDeviceId);
        if (fcmState != null) {
            double rtt = FogTopologyUtil.estimateRttMs(candidate, deviceIndex.get(resp.fcmDeviceId), deviceIndex);
            fcmState.getCrt().mergeDevice(candidate, rtt);
            candidateState.setCrt(fcmState.getCrt());
            candidateState.setGrt(fcmState.getGrt());
            fcmState.setLastAdvertiseTimeMs(currentTimeMs);
            markFcmDirty(resp.fcmDeviceId);
        }
    }

    /**
     * Query only nearby FCMs (area-pruned), respond with top-K by RTT.
     */
    private List<ColonyAdvertisement> queryColoniesPruned(FogDevice node, double currentTimeMs) {
        List<ColonyAdvertisement> candidates = new ArrayList<>();
        double wt = DynaColConfig.waitThresholdMs();
        for (FogNodeState peer : nodeStates.values()) {
            if (!peer.isFcm() || peer.getFcmDeviceId() == null) {
                continue;
            }
            if (!bootstrapPhase
                    && (peer.getLastAdvertiseTimeMs() < 0.0
                    || peer.getLastAdvertiseTimeMs() + wt < currentTimeMs)) {
                continue;
            }
            FogDevice fcmDevice = deviceIndex.get(peer.getFcmDeviceId());
            if (fcmDevice == null || !isWithinFormationRadius(node, fcmDevice)) {
                continue;
            }
            double rtt = FogTopologyUtil.estimateRttMs(node, fcmDevice, deviceIndex);
            if (rtt > DynaColConfig.maxColonyRttMs()) {
                continue;
            }
            candidates.add(new ColonyAdvertisement(
                    peer.getColonyId(),
                    peer.getFcmDeviceId(),
                    peer.getCrt(),
                    rtt
            ));
        }
        candidates.sort(Comparator.comparingDouble(r -> r.rttMs));
        int limit = Math.min(DynaColConfig.TOP_K_FORMATION_QUERIES, candidates.size());
        List<ColonyAdvertisement> pruned = new ArrayList<>(candidates.subList(0, limit));
        for (int i = 0; i < pruned.size(); i++) {
            ControlOverheadMonitor.getInstance().recordAdvertise();
        }
        return pruned;
    }

    private boolean isWithinFormationRadius(FogDevice a, FogDevice b) {
        int ai = FogTopologyUtil.areaIndex(a);
        int bi = FogTopologyUtil.areaIndex(b);
        if (ai < 0 || bi < 0) {
            return true;
        }
        return Math.abs(ai - bi) <= DynaColConfig.FORMATION_AREA_RADIUS;
    }

    private ColonyAdvertisement selectBestResponse(List<ColonyAdvertisement> responses) {
        if (responses.isEmpty()) {
            return null;
        }
        ColonyAdvertisement best = responses.get(0);
        double bestScore = scoreFormationCandidate(best);
        for (int i = 1; i < responses.size(); i++) {
            ColonyAdvertisement candidate = responses.get(i);
            double score = scoreFormationCandidate(candidate);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Multi-criteria formation score (lower is better): RTT, CRT headroom, FCM potency, colony-size balance.
     */
    private double scoreFormationCandidate(ColonyAdvertisement ad) {
        double maxRtt = Math.max(1.0, DynaColConfig.maxColonyRttMs());
        double rttNorm = ad.rttMs / maxRtt;

        ResourceVector agg = ad.crt.aggregateAvailable();
        ResourceVector norm = ResourceVector.normalize(agg, maxResources);
        double headroom = (norm.getCpu() + norm.getRam()) / 2.0;
        double headroomPenalty = 1.0 - headroom;

        FogDevice fcmDevice = deviceIndex.get(ad.fcmDeviceId);
        double potency = fcmDevice != null ? potencyCalculator.potency(fcmDevice) : 0.0;
        double potencyPenalty = 1.0 - potency;

        int memberCount = ad.crt.asList().size();
        int targetSize = estimatedTargetColonySize();
        double balancePenalty = Math.abs(memberCount - targetSize) / (double) Math.max(1, targetSize);

        return DynaColConfig.FORMATION_W_RTT * rttNorm
                + DynaColConfig.FORMATION_W_HEADROOM * headroomPenalty
                + DynaColConfig.FORMATION_W_POTENCY * potencyPenalty
                + DynaColConfig.FORMATION_W_BALANCE * balancePenalty;
    }

    private int estimatedTargetColonySize() {
        int totalFog = 0;
        for (FogDevice device : allDevices) {
            if (device.getParentId() != -1) {
                totalFog++;
            }
        }
        int fcmCount = 0;
        for (FogNodeState state : nodeStates.values()) {
            if (state.isFcm()) {
                fcmCount++;
            }
        }
        return Math.max(2, totalFog / Math.max(1, fcmCount + 1));
    }

    private void selfElectAsFcm(FogDevice node, FogNodeState state, double currentTimeMs) {
        state.setState(NodeMembershipState.FCM);
        state.setFcmDeviceId(node.getId());
        state.setColonyId(generateColonyId(node.getId()));
        state.setCrt(ColonyResourceTable.initFromDevice(node, 0.0));
        state.setGrt(new GlobalResourceTable());
        state.setLastAdvertiseTimeMs(currentTimeMs);
        ControlOverheadMonitor.getInstance().recordAdvertise();
        scheduleGrtRefresh(state, currentTimeMs);
    }

    /** Refresh advertisement timestamp for an active FCM (periodic advertise events). */
    public void recordFcmAdvertisement(int fcmDeviceId, double currentTimeMs) {
        FogNodeState fcmState = nodeStates.get(fcmDeviceId);
        if (fcmState != null && fcmState.isFcm()) {
            fcmState.setLastAdvertiseTimeMs(currentTimeMs);
            ControlOverheadMonitor.getInstance().recordAdvertise();
        }
    }

    public void refreshNeighborGrt(FogNodeState fcmState, double currentTimeMs) {
        GlobalResourceTable grt = fcmState.getGrt();
        grt.remove(fcmState.getColonyId());
        FogDevice localFcm = deviceIndex.get(fcmState.getFcmDeviceId());
        if (localFcm == null) {
            return;
        }
        for (FogNodeState peer : nodeStates.values()) {
            if (!peer.isFcm() || peer.getColonyId() == null
                    || peer.getColonyId().equals(fcmState.getColonyId())) {
                continue;
            }
            FogDevice peerFcm = deviceIndex.get(peer.getFcmDeviceId());
            if (peerFcm == null || !isWithinFormationRadius(localFcm, peerFcm)) {
                continue;
            }
            double rtt = FogTopologyUtil.estimateRttMs(localFcm, peerFcm, deviceIndex);
            if (rtt <= DynaColConfig.maxColonyRttMs() * 2) {
                grt.updateFromColony(
                        peer.getColonyId(),
                        peer.getFcmDeviceId(),
                        peer.getCrt().aggregateAvailable(),
                        rtt
                );
            }
        }
        propagateGrtToMembers(fcmState);
        dirtyFcmIds.remove(fcmState.getFcmDeviceId());
    }

    /** Incremental GRT: only refresh FCMs marked dirty since last pass. */
    public void refreshDirtyGrt(double currentTimeMs, boolean forceAll) {
        if (forceAll) {
            dirtyFcmIds.clear();
            for (FogNodeState state : nodeStates.values()) {
                if (state.isFcm()) {
                    dirtyFcmIds.add(state.getFcmDeviceId());
                }
            }
        }
        if (dirtyFcmIds.isEmpty()) {
            return;
        }
        List<Integer> toRefresh = new ArrayList<>(dirtyFcmIds);
        dirtyFcmIds.clear();
        for (int fcmId : toRefresh) {
            FogNodeState state = nodeStates.get(fcmId);
            if (state != null && state.isFcm()) {
                refreshNeighborGrt(state, currentTimeMs);
            }
        }
    }

    public void refreshAllFcmGrt(double currentTimeMs) {
        refreshDirtyGrt(currentTimeMs, true);
    }

    public void markFcmDirty(int fcmDeviceId) {
        dirtyFcmIds.add(fcmDeviceId);
    }

    private void scheduleGrtRefresh(FogNodeState fcmState, double currentTimeMs) {
        if (bootstrapPhase) {
            markFcmDirty(fcmState.getFcmDeviceId());
            markAdjacentFcmsDirty(fcmState);
        } else {
            refreshNeighborGrt(fcmState, currentTimeMs);
            markAdjacentFcmsDirty(fcmState);
            refreshDirtyGrt(currentTimeMs, false);
        }
    }

    private void markAdjacentFcmsDirty(FogNodeState fcmState) {
        FogDevice local = deviceIndex.get(fcmState.getFcmDeviceId());
        if (local == null) {
            return;
        }
        for (FogNodeState peer : nodeStates.values()) {
            if (!peer.isFcm() || peer.getFcmDeviceId() == null) {
                continue;
            }
            FogDevice peerDevice = deviceIndex.get(peer.getFcmDeviceId());
            if (peerDevice != null && isWithinFormationRadius(local, peerDevice)) {
                dirtyFcmIds.add(peer.getFcmDeviceId());
            }
        }
    }

    private void propagateGrtToMembers(FogNodeState fcmState) {
        for (FogNodeState member : nodeStates.values()) {
            if (fcmState.getColonyId().equals(member.getColonyId())) {
                member.setGrt(fcmState.getGrt());
            }
        }
    }

    private long generateColonyId(int fcmId) {
        return ((long) fcmId << 32) ^ random.nextInt();
    }

    public static class ColonyAdvertisement {
        public final long colonyId;
        public final int fcmDeviceId;
        public final ColonyResourceTable crt;
        public final double rttMs;

        public ColonyAdvertisement(long colonyId, int fcmDeviceId, ColonyResourceTable crt, double rttMs) {
            this.colonyId = colonyId;
            this.fcmDeviceId = fcmDeviceId;
            this.crt = crt;
            this.rttMs = rttMs;
        }
    }
}
