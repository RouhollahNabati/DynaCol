package org.fog.dynacol.colony;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.DynaColFeatureFlags;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.NodeMembershipState;
import org.fog.dynacol.table.ColonyResourceTable;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Merges nearby provisional colonies when a stronger FCM is within RTT range.
 */
public class ColonyMergeService {

    private final Map<Integer, FogNodeState> nodeStates;
    private final Map<Integer, FogDevice> deviceIndex;
    private final PotencyCalculator potencyCalculator;
    private final ColonyFormationProtocol formationProtocol;
    private final DynaColFeatureFlags flags;

    public ColonyMergeService(Map<Integer, FogNodeState> nodeStates,
                              List<FogDevice> allDevices,
                              ColonyFormationProtocol formationProtocol) {
        this(nodeStates, allDevices, formationProtocol, DynaColFeatureFlags.full());
    }

    public ColonyMergeService(Map<Integer, FogNodeState> nodeStates,
                              List<FogDevice> allDevices,
                              ColonyFormationProtocol formationProtocol,
                              DynaColFeatureFlags flags) {
        this.nodeStates = nodeStates;
        this.deviceIndex = org.fog.dynacol.util.FogTopologyUtil.indexById(allDevices);
        this.potencyCalculator = new PotencyCalculator(allDevices);
        this.formationProtocol = formationProtocol;
        this.flags = flags != null ? flags : DynaColFeatureFlags.full();
    }

    public void attemptMerges(double currentTimeMs) {
        if (!flags.isHandoverEnabled()) {
            return;
        }
        attemptMergesWithHysteresis(currentTimeMs, DynaColConfig.HANDOVER_HYSTERESIS);
    }

    /**
     * Post-bootstrap pass: merge undersized colonies and relax potency hysteresis to reduce fragmentation.
     */
    public void consolidateAfterBootstrap(double currentTimeMs) {
        if (!flags.isHandoverEnabled()) {
            return;
        }
        for (int pass = 0; pass < DynaColConfig.CONSOLIDATE_MAX_PASSES; pass++) {
            boolean merged = mergeSmallColonies(currentTimeMs);
            merged |= attemptMergesWithHysteresis(currentTimeMs, DynaColConfig.CONSOLIDATE_MERGE_HYSTERESIS);
            if (!merged) {
                break;
            }
        }
    }

    private boolean attemptMergesWithHysteresis(double currentTimeMs, double hysteresis) {
        List<FogNodeState> fcms = new ArrayList<>();
        for (FogNodeState state : nodeStates.values()) {
            if (state.isFcm()) {
                fcms.add(state);
            }
        }

        boolean merged = false;
        for (int i = 0; i < fcms.size(); i++) {
            FogNodeState a = fcms.get(i);
            if (!a.isFcm()) {
                continue;
            }
            for (int j = i + 1; j < fcms.size(); j++) {
                FogNodeState b = fcms.get(j);
                if (!b.isFcm() || a.getColonyId().equals(b.getColonyId())) {
                    continue;
                }
                FogDevice fcmA = deviceIndex.get(a.getFcmDeviceId());
                FogDevice fcmB = deviceIndex.get(b.getFcmDeviceId());
                if (fcmA == null || fcmB == null) {
                    continue;
                }
                double rtt = org.fog.dynacol.util.FogTopologyUtil.estimateRttMs(fcmA, fcmB, deviceIndex);
                if (rtt > DynaColConfig.maxColonyRttMs()) {
                    continue;
                }
                if (!sameAreaOrAdjacent(fcmA, fcmB) && rtt > DynaColConfig.maxColonyRttMs() * 0.6) {
                    continue;
                }
                if (mergeIfNeeded(a, b, currentTimeMs, hysteresis)) {
                    merged = true;
                }
            }
        }
        return merged;
    }

    private boolean mergeSmallColonies(double currentTimeMs) {
        List<FogNodeState> smallFcms = new ArrayList<>();
        for (FogNodeState state : nodeStates.values()) {
            if (state.isFcm() && state.getCrt().asList().size() < DynaColConfig.CONSOLIDATE_MIN_COLONY_MEMBERS) {
                smallFcms.add(state);
            }
        }

        boolean merged = false;
        for (FogNodeState small : smallFcms) {
            if (!small.isFcm()) {
                continue;
            }
            FogDevice smallDevice = deviceIndex.get(small.getFcmDeviceId());
            if (smallDevice == null) {
                continue;
            }

            FogNodeState bestNeighbor = null;
            double bestRtt = Double.MAX_VALUE;
            for (FogNodeState peer : nodeStates.values()) {
                if (!peer.isFcm() || peer.getColonyId().equals(small.getColonyId())) {
                    continue;
                }
                FogDevice peerDevice = deviceIndex.get(peer.getFcmDeviceId());
                if (peerDevice == null) {
                    continue;
                }
                double rtt = org.fog.dynacol.util.FogTopologyUtil.estimateRttMs(smallDevice, peerDevice, deviceIndex);
                if (rtt <= DynaColConfig.maxColonyRttMs() && rtt < bestRtt) {
                    bestRtt = rtt;
                    bestNeighbor = peer;
                }
            }
            if (bestNeighbor != null
                    && mergeIfNeeded(bestNeighbor, small, currentTimeMs, DynaColConfig.CONSOLIDATE_MERGE_HYSTERESIS)) {
                merged = true;
            }
        }
        return merged;
    }

    private boolean mergeIfNeeded(FogNodeState first, FogNodeState second, double currentTimeMs) {
        return mergeIfNeeded(first, second, currentTimeMs, DynaColConfig.HANDOVER_HYSTERESIS);
    }

    private boolean mergeIfNeeded(FogNodeState first, FogNodeState second, double currentTimeMs, double hysteresis) {
        FogDevice fcm1 = deviceIndex.get(first.getFcmDeviceId());
        FogDevice fcm2 = deviceIndex.get(second.getFcmDeviceId());
        if (fcm1 == null || fcm2 == null) {
            return false;
        }

        double p1 = potencyCalculator.potency(fcm1);
        double p2 = potencyCalculator.potency(fcm2);
        FogNodeState strong = p1 >= p2 ? first : second;
        FogNodeState weak = p1 >= p2 ? second : first;
        FogDevice strongDevice = deviceIndex.get(strong.getFcmDeviceId());
        FogDevice weakDevice = deviceIndex.get(weak.getFcmDeviceId());
        if (strongDevice == null || weakDevice == null) {
            return false;
        }

        double strongPotency = potencyCalculator.potency(strongDevice);
        double weakPotency = potencyCalculator.potency(weakDevice);
        if (strongPotency <= weakPotency + hysteresis) {
            return false;
        }
        if (currentTimeMs - weak.getLastHandoverTime() < DynaColConfig.HANDOVER_COOLDOWN_MS
                && currentTimeMs - strong.getLastHandoverTime() < DynaColConfig.HANDOVER_COOLDOWN_MS) {
            return false;
        }

        executeMerge(strong, weak, currentTimeMs);
        return true;
    }

    private void executeMerge(FogNodeState strong, FogNodeState weak, double currentTimeMs) {
        ControlOverheadMonitor.getInstance().recordHandover();
        long colonyId = strong.getColonyId();
        Long absorbedColonyId = weak.getColonyId();

        ColonyResourceTable merged = new ColonyResourceTable();
        for (ColonyResourceEntry entry : strong.getCrt().asList()) {
            merged.put(entry.getFogDeviceId(), entry);
        }
        for (ColonyResourceEntry entry : weak.getCrt().asList()) {
            if (merged.get(entry.getFogDeviceId()) == null) {
                merged.put(entry.getFogDeviceId(), entry);
            }
        }

        strong.setCrt(merged);
        strong.setLastHandoverTime(currentTimeMs);

        weak.setState(NodeMembershipState.MEMBER);
        weak.setColonyId(colonyId);
        weak.setFcmDeviceId(strong.getFcmDeviceId());
        weak.setCrt(merged);
        weak.setGrt(strong.getGrt());
        weak.setLastHandoverTime(currentTimeMs);

        if (absorbedColonyId != null) {
            for (FogNodeState member : nodeStates.values()) {
                if (absorbedColonyId.equals(member.getColonyId())
                        && member.getFogDeviceId() != strong.getFogDeviceId()) {
                    member.setColonyId(colonyId);
                    member.setFcmDeviceId(strong.getFcmDeviceId());
                    member.setState(NodeMembershipState.MEMBER);
                    member.setCrt(merged);
                    member.setGrt(strong.getGrt());
                }
            }
        }

        formationProtocol.markFcmDirty(strong.getFcmDeviceId());
        formationProtocol.markFcmDirty(weak.getFcmDeviceId());
    }

    private boolean sameAreaOrAdjacent(FogDevice a, FogDevice b) {
        int ai = org.fog.dynacol.util.FogTopologyUtil.areaIndex(a);
        int bi = org.fog.dynacol.util.FogTopologyUtil.areaIndex(b);
        if (ai < 0 || bi < 0) {
            return true;
        }
        return Math.abs(ai - bi) <= 1;
    }
}
