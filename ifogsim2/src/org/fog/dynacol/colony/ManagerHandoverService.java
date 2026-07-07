package org.fog.dynacol.colony;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.DynaColFeatureFlags;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.NodeMembershipState;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.table.ColonyResourceTable;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.List;
import java.util.Map;

/**
 * Algorithm 2: JoinOrNegotiate — potency-aware manager handover.
 */
public class ManagerHandoverService {

    private final Map<Integer, FogNodeState> nodeStates;
    private final Map<Integer, FogDevice> deviceIndex;
    private final PotencyCalculator potencyCalculator;
    private final DynaColFeatureFlags flags;

    public ManagerHandoverService(Map<Integer, FogNodeState> nodeStates,
                                  List<FogDevice> allDevices) {
        this(nodeStates, allDevices, DynaColFeatureFlags.full());
    }

    public ManagerHandoverService(Map<Integer, FogNodeState> nodeStates,
                                  List<FogDevice> allDevices,
                                  DynaColFeatureFlags flags) {
        this.nodeStates = nodeStates;
        this.deviceIndex = FogTopologyUtil.indexById(allDevices);
        this.potencyCalculator = new PotencyCalculator(allDevices);
        this.flags = flags != null ? flags : DynaColFeatureFlags.full();
    }

    public void joinOrNegotiate(FogDevice candidate,
                              FogNodeState candidateState,
                              ColonyFormationProtocol.ColonyAdvertisement resp,
                              double currentTimeMs) {
        FogDevice currentFcm = deviceIndex.get(resp.fcmDeviceId);
        FogNodeState fcmState = nodeStates.get(resp.fcmDeviceId);

        double candidatePotency = potencyCalculator.potency(candidate);
        double currentPotency = potencyCalculator.potency(currentFcm);

        if (!flags.isHandoverEnabled()) {
            joinAsMember(candidate, candidateState, resp, currentTimeMs);
            return;
        }

        if (candidateState.getState() == NodeMembershipState.FCM
                || candidatePotency > currentPotency + DynaColConfig.HANDOVER_HYSTERESIS) {
            if (fcmState != null
                    && currentTimeMs - fcmState.getLastHandoverTime() >= DynaColConfig.HANDOVER_COOLDOWN_MS) {
                executeHandover(candidate, candidateState, fcmState, resp, currentTimeMs);
                return;
            }
        }

        joinAsMember(candidate, candidateState, resp, currentTimeMs);
    }

    private void executeHandover(FogDevice candidate,
                                 FogNodeState candidateState,
                                 FogNodeState oldFcmState,
                                 ColonyFormationProtocol.ColonyAdvertisement resp,
                                 double currentTimeMs) {
        ControlOverheadMonitor.getInstance().recordHandover();

        long colonyId = oldFcmState.getColonyId();
        ColonyResourceTable merged = new ColonyResourceTable();
        for (ColonyResourceEntry entry : resp.crt.asList()) {
            merged.put(entry.getFogDeviceId(), entry);
        }
        double rtt = FogTopologyUtil.estimateRttMs(candidate, candidate, deviceIndex);
        merged.mergeDevice(candidate, rtt);

        candidateState.setState(NodeMembershipState.FCM);
        candidateState.setFcmDeviceId(candidate.getId());
        candidateState.setColonyId(colonyId);
        candidateState.setCrt(merged);
        candidateState.setGrt(oldFcmState.getGrt());
        candidateState.setLastHandoverTime(currentTimeMs);
        candidateState.setLastAdvertiseTimeMs(currentTimeMs);

        oldFcmState.setState(NodeMembershipState.MEMBER);
        oldFcmState.setFcmDeviceId(candidate.getId());

        for (FogNodeState member : nodeStates.values()) {
            Long memberColonyId = member.getColonyId();
            if (memberColonyId != null && memberColonyId == colonyId) {
                member.setFcmDeviceId(candidate.getId());
                if (member.getFogDeviceId() != candidate.getId()) {
                    member.setState(NodeMembershipState.MEMBER);
                }
            }
        }
    }

    private void joinAsMember(FogDevice candidate,
                              FogNodeState candidateState,
                              ColonyFormationProtocol.ColonyAdvertisement resp,
                              double currentTimeMs) {
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
        }
    }
}
