package org.fog.dynacol.colony;

import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.NodeMembershipState;
import org.fog.dynacol.placement.PlacementCandidateUtil;
import org.fog.dynacol.table.ColonyResourceTable;
import org.fog.dynacol.table.GlobalResourceTable;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-defined per-area colonies (no cold-start bootstrap). Each area fog router (d-*)
 * is the FCM for its cameras; GRT links neighboring area colonies.
 */
public final class StaticClusterOverlayBuilder {

    private StaticClusterOverlayBuilder() {
    }

    public static void build(Map<Integer, FogNodeState> nodeStates,
                             List<FogDevice> allDevices) {
        buildPartial(nodeStates, allDevices, allDevices);
    }

    /**
     * Build static overlay for an initial subset; remaining devices stay UNASSIGNED until joinLateNode.
     */
    public static void buildPartial(Map<Integer, FogNodeState> nodeStates,
                                    List<FogDevice> allDevices,
                                    List<FogDevice> bootstrappedDevices) {
        java.util.Set<Integer> activeIds = new java.util.HashSet<>();
        for (FogDevice device : bootstrappedDevices) {
            activeIds.add(device.getId());
        }
        Map<Integer, FogDevice> deviceIndex = FogTopologyUtil.indexById(allDevices);
        List<FogDevice> areaRouters = new ArrayList<>();
        for (FogDevice device : allDevices) {
            if (PlacementCandidateUtil.isFogHost(device) && activeIds.contains(device.getId())) {
                areaRouters.add(device);
            }
        }

        for (FogDevice router : areaRouters) {
            long colonyId = ((long) router.getId() << 32) ^ FogTopologyUtil.areaIndex(router);

            ColonyResourceTable crt = ColonyResourceTable.initFromDevice(router, 0.0);
            for (FogDevice device : allDevices) {
                if (device.getParentId() == -1 || !activeIds.contains(device.getId())) {
                    continue;
                }
                if (resolveAreaRouter(device, deviceIndex) == router) {
                    double rtt = FogTopologyUtil.estimateRttMs(device, router, deviceIndex);
                    crt.mergeDevice(device, rtt);
                }
            }

            FogNodeState fcmState = nodeStates.computeIfAbsent(router.getId(), FogNodeState::new);
            fcmState.setState(NodeMembershipState.FCM);
            fcmState.setFcmDeviceId(router.getId());
            fcmState.setColonyId(colonyId);
            fcmState.setCrt(crt);
            fcmState.setGrt(new GlobalResourceTable());
            fcmState.setNearestCloudRttMs(FogTopologyUtil.nearestCloudRtt(router, allDevices, deviceIndex));
        }

        for (FogDevice device : bootstrappedDevices) {
            if (device.getParentId() == -1) {
                continue;
            }
            assignToAreaColony(nodeStates, device, deviceIndex, allDevices);
        }

        refreshAllNeighborGrt(nodeStates, deviceIndex);
    }

    /** Join a late-arriving device into its area-router colony (no handover). */
    public static void joinLateNode(Map<Integer, FogNodeState> nodeStates,
                                    List<FogDevice> allDevices,
                                    FogDevice device) {
        if (device == null || device.getParentId() == -1) {
            return;
        }
        Map<Integer, FogDevice> deviceIndex = FogTopologyUtil.indexById(allDevices);
        FogDevice areaRouter = resolveAreaRouter(device, deviceIndex);
        if (areaRouter == null) {
            return;
        }
        FogNodeState fcmState = nodeStates.get(areaRouter.getId());
        if (fcmState == null || !fcmState.isFcm()) {
            StaticClusterOverlayBuilder.buildPartial(nodeStates, allDevices,
                    java.util.Collections.singletonList(areaRouter));
            fcmState = nodeStates.get(areaRouter.getId());
        }
        if (fcmState == null) {
            return;
        }
        double rtt = FogTopologyUtil.estimateRttMs(device, areaRouter, deviceIndex);
        fcmState.getCrt().mergeDevice(device, rtt);
        FogNodeState state = nodeStates.computeIfAbsent(device.getId(), FogNodeState::new);
        boolean isFcm = device.getId() == areaRouter.getId();
        state.setState(isFcm ? NodeMembershipState.FCM : NodeMembershipState.MEMBER);
        state.setFcmDeviceId(areaRouter.getId());
        state.setColonyId(fcmState.getColonyId());
        state.setCrt(fcmState.getCrt());
        state.setGrt(fcmState.getGrt());
        state.setNearestCloudRttMs(FogTopologyUtil.nearestCloudRtt(device, allDevices, deviceIndex));
        refreshAllNeighborGrt(nodeStates, deviceIndex);
    }

    private static void assignToAreaColony(Map<Integer, FogNodeState> nodeStates,
                                           FogDevice device,
                                           Map<Integer, FogDevice> deviceIndex,
                                           List<FogDevice> allDevices) {
        FogDevice areaRouter = resolveAreaRouter(device, deviceIndex);
        if (areaRouter == null) {
            return;
        }
        FogNodeState fcmState = nodeStates.get(areaRouter.getId());
        if (fcmState == null) {
            return;
        }
        FogNodeState state = nodeStates.computeIfAbsent(device.getId(), FogNodeState::new);
        boolean isFcm = device.getId() == areaRouter.getId();
        state.setState(isFcm ? NodeMembershipState.FCM : NodeMembershipState.MEMBER);
        state.setFcmDeviceId(areaRouter.getId());
        state.setColonyId(fcmState.getColonyId());
        state.setCrt(fcmState.getCrt());
        state.setGrt(fcmState.getGrt());
        state.setNearestCloudRttMs(FogTopologyUtil.nearestCloudRtt(device, allDevices, deviceIndex));
    }

    private static void refreshAllNeighborGrt(Map<Integer, FogNodeState> nodeStates,
                                                Map<Integer, FogDevice> deviceIndex) {
        for (FogNodeState fcmState : nodeStates.values()) {
            if (!fcmState.isFcm()) {
                continue;
            }
            GlobalResourceTable grt = fcmState.getGrt();
            grt.remove(fcmState.getColonyId());
            FogDevice localFcm = deviceIndex.get(fcmState.getFcmDeviceId());
            if (localFcm == null) {
                continue;
            }
            for (FogNodeState peer : nodeStates.values()) {
                if (!peer.isFcm() || peer.getColonyId().equals(fcmState.getColonyId())) {
                    continue;
                }
                FogDevice peerFcm = deviceIndex.get(peer.getFcmDeviceId());
                if (peerFcm == null) {
                    continue;
                }
                double rtt = FogTopologyUtil.estimateRttMs(localFcm, peerFcm, deviceIndex);
                if (rtt <= org.fog.dynacol.DynaColConfig.maxColonyRttMs() * 2) {
                    grt.updateFromColony(
                            peer.getColonyId(),
                            peer.getFcmDeviceId(),
                            peer.getCrt().aggregateAvailable(),
                            rtt
                    );
                }
            }
            propagateGrtToMembers(fcmState, nodeStates);
        }
    }

    private static void propagateGrtToMembers(FogNodeState fcmState, Map<Integer, FogNodeState> nodeStates) {
        for (FogNodeState member : nodeStates.values()) {
            if (fcmState.getColonyId().equals(member.getColonyId())) {
                member.setGrt(fcmState.getGrt());
            }
        }
    }

    private static FogDevice resolveAreaRouter(FogDevice device, Map<Integer, FogDevice> deviceIndex) {
        FogDevice cur = device;
        while (cur != null && cur.getParentId() != -1) {
            if (PlacementCandidateUtil.isFogHost(cur)) {
                return cur;
            }
            cur = deviceIndex.get(cur.getParentId());
        }
        return null;
    }
}
