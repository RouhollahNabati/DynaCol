package org.fog.dynacol.runtime;

import org.fog.dynacol.DynaColFeatureFlags;
import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.colony.ColonyFormationProtocol;
import org.fog.dynacol.colony.StaticClusterOverlayBuilder;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.NodeMembershipState;
import org.fog.dynacol.placement.PlacementCandidateUtil;
import org.fog.dynacol.placement.PlacementPolicy;
import org.fog.dynacol.placement.PlacementStrategy;
import org.fog.dynacol.table.ColonyResourceTable;
import org.fog.dynacol.table.GlobalResourceTable;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Cold-start bootstrap: processes fog nodes in arrival order before simulation.
 */
public class DynaColBootstrap {

    private final Map<Integer, FogNodeState> nodeStates = new HashMap<>();
    private final List<FogDevice> allDevices;
    private final Random random;
    private PlacementPolicy placementPolicy = PlacementPolicy.DYNACOL_DCBO;
    private DynaColFeatureFlags featureFlags = DynaColFeatureFlags.full();
    private double bootstrapFraction = 1.0;
    private double slaDeadlineMs = DynaColConfig.DEFAULT_SLA_DEADLINE_MS;

    public DynaColBootstrap(List<FogDevice> allDevices, long seed) {
        this.allDevices = allDevices;
        this.random = new Random(seed);
    }

    public void setBootstrapFraction(double bootstrapFraction) {
        this.bootstrapFraction = Math.max(0.0, Math.min(1.0, bootstrapFraction));
    }

    public void setSlaDeadlineMs(double slaDeadlineMs) {
        if (slaDeadlineMs > 0.0) {
            this.slaDeadlineMs = slaDeadlineMs;
        }
    }

    public DynaColRuntime run(List<FogDevice> arrivalOrder) {
        List<FogDevice> fogArrivals = filterFogArrivals(arrivalOrder);
        int split = bootstrapFraction >= 1.0 ? fogArrivals.size()
                : Math.max(1, (int) Math.round(fogArrivals.size() * bootstrapFraction));
        List<FogDevice> initial = fogArrivals.subList(0, Math.min(split, fogArrivals.size()));
        List<FogDevice> late = split < fogArrivals.size()
                ? new ArrayList<>(fogArrivals.subList(split, fogArrivals.size()))
                : new ArrayList<>();
        return runInternal(arrivalOrder, initial, late);
    }

    private static List<FogDevice> filterFogArrivals(List<FogDevice> arrivalOrder) {
        List<FogDevice> fogArrivals = new ArrayList<>();
        for (FogDevice node : arrivalOrder) {
            if (node.getParentId() != -1) {
                fogArrivals.add(node);
            }
        }
        return fogArrivals;
    }

    private DynaColRuntime runInternal(List<FogDevice> fullOrder,
                                       List<FogDevice> initialArrivals,
                                       List<FogDevice> lateArrivals) {
        ControlOverheadMonitor.getInstance().reset();
        ColonyFormationProtocol formation = null;
        OverlayBootstrapMode overlayMode = overlayMode(placementPolicy);

        switch (overlayMode) {
            case COLD_START:
                if (featureFlags.isColonyOverlayEnabled()) {
                    DynaColConfig.autoTuneMaxColonyRttFromTopology(allDevices, slaDeadlineMs);
                    formation = new ColonyFormationProtocol(nodeStates, allDevices, random, featureFlags);
                    double time = 0.0;
                    for (FogDevice node : initialArrivals) {
                        formation.createAndAddFogNode(node, time);
                        time += 5.0;
                    }
                    formation.finalizeColdStartBootstrap(time);
                } else {
                    buildFlatOverlay(initialArrivals);
                }
                break;
            case STATIC_PRECLUSTERED:
                if (lateArrivals.isEmpty()) {
                    StaticClusterOverlayBuilder.build(nodeStates, allDevices);
                } else {
                    StaticClusterOverlayBuilder.buildPartial(nodeStates, allDevices, initialArrivals);
                }
                break;
            case FLAT:
                buildFlatOverlay(initialArrivals.isEmpty() ? fullOrder : initialArrivals);
                break;
            case NONE:
            default:
                break;
        }

        PlacementStrategy strategy = placementPolicy.create(nodeStates, allDevices, random, featureFlags);
        DynaColRuntime runtime = new DynaColRuntime(nodeStates, strategy, placementPolicy, featureFlags);
        runtime.setFormationProtocol(formation);
        runtime.setAllDevices(allDevices);
        runtime.setLateArrivalOrder(lateArrivals);
        runtime.setIncrementalArrivalMode(!lateArrivals.isEmpty());
        DynaColPlacementManager placementManager = new DynaColPlacementManager(runtime, allDevices);
        runtime.setPlacementManager(placementManager);
        return runtime;
    }

    private void buildFlatOverlay(List<FogDevice> arrivalOrder) {
        FogDevice hub = allDevices.stream()
                .filter(d -> "proxy-server".equals(d.getName()))
                .findFirst()
                .orElse(allDevices.stream()
                        .filter(d -> d.getName().startsWith("d-"))
                        .findFirst()
                        .orElse(null));
        if (hub == null) {
            return;
        }

        ColonyResourceTable sharedCrt = new ColonyResourceTable();
        for (FogDevice device : allDevices) {
            if (PlacementCandidateUtil.isFogHost(device)) {
                sharedCrt.mergeDevice(device, 0.0);
            }
        }

        FogNodeState hubState = nodeStates.computeIfAbsent(hub.getId(), FogNodeState::new);
        hubState.setState(NodeMembershipState.FCM);
        hubState.setFcmDeviceId(hub.getId());
        hubState.setColonyId(1L);
        hubState.setCrt(sharedCrt);
        hubState.setGrt(new GlobalResourceTable());

        for (FogDevice node : arrivalOrder) {
            if (node.getParentId() == -1) {
                continue;
            }
            FogNodeState state = nodeStates.computeIfAbsent(node.getId(), FogNodeState::new);
            state.setState(node.getId() == hub.getId() ? NodeMembershipState.FCM : NodeMembershipState.MEMBER);
            state.setFcmDeviceId(hub.getId());
            state.setColonyId(1L);
            state.setCrt(sharedCrt);
            state.setGrt(new GlobalResourceTable());
        }
    }

    public void setPlacementPolicy(PlacementPolicy placementPolicy) {
        this.placementPolicy = placementPolicy;
    }

    public void setFeatureFlags(DynaColFeatureFlags featureFlags) {
        this.featureFlags = featureFlags != null ? featureFlags : DynaColFeatureFlags.full();
    }

    public Map<Integer, FogNodeState> getNodeStates() {
        return nodeStates;
    }

    public static OverlayBootstrapMode overlayMode(PlacementPolicy policy) {
        if (policy == null) {
            return OverlayBootstrapMode.COLD_START;
        }
        switch (policy) {
            case DYNACOL_DCBO:
                return OverlayBootstrapMode.COLD_START;
            case STATIC_DCBO:
                return OverlayBootstrapMode.STATIC_PRECLUSTERED;
            case OFFLINE_GA:
            case GA_HC_STYLE:
                return OverlayBootstrapMode.STATIC_PRECLUSTERED;
            case FOGPLAN_MIN_COST:
            case FOGPLAN_STYLE:
            case FOGPLAN_CENTRALIZED:
                return OverlayBootstrapMode.FLAT;
            case EDGEWARD:
                return OverlayBootstrapMode.NONE;
            default:
                return OverlayBootstrapMode.FLAT;
        }
    }

    public static boolean usesColonyOverlay(PlacementPolicy policy) {
        OverlayBootstrapMode mode = overlayMode(policy);
        return mode == OverlayBootstrapMode.COLD_START
                || mode == OverlayBootstrapMode.STATIC_PRECLUSTERED;
    }
}
