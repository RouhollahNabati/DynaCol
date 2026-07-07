package org.fog.dynacol.controller;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.Application;
import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.colony.ColonyFormationProtocol;
import org.fog.dynacol.colony.StaticClusterOverlayBuilder;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.NodeMembershipState;
import org.fog.dynacol.model.ResourceVector;
import org.fog.dynacol.runtime.DynaColArrivalScheduler;
import org.fog.dynacol.runtime.DynaColPlacementManager;
import org.fog.dynacol.runtime.DynaColRuntime;
import org.fog.dynacol.placement.PlacementPolicy;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.Controller;
import org.fog.placement.ModulePlacement;
import org.fog.utils.Config;
import org.fog.utils.FogEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller extension with runtime DynaCol colony refresh, DCBO re-placement, and scenario events.
 */
public class DynaColController extends Controller {

    private final DynaColRuntime runtime;
    private final String scenarioKey;
    private final long scenarioSeed;
    private List<FogDevice> arrivalOrder = new ArrayList<>();
    private boolean scenarioEventsScheduled;

    public DynaColController(String name,
                             List<FogDevice> fogDevices,
                             List<Sensor> sensors,
                             List<Actuator> actuators,
                             DynaColRuntime runtime) {
        this(name, fogDevices, sensors, actuators, runtime, "normal", 0L);
    }

    public DynaColController(String name,
                             List<FogDevice> fogDevices,
                             List<Sensor> sensors,
                             List<Actuator> actuators,
                             DynaColRuntime runtime,
                             String scenarioKey,
                             long scenarioSeed) {
        super(name, fogDevices, sensors, actuators);
        this.runtime = runtime;
        this.scenarioKey = scenarioKey != null ? scenarioKey : "normal";
        this.scenarioSeed = scenarioSeed;
    }

    public void setArrivalOrder(List<FogDevice> arrivalOrder) {
        this.arrivalOrder = arrivalOrder != null ? arrivalOrder : new ArrayList<>();
    }

    @Override
    public void startEntity() {
        super.startEntity();
        scheduleScenarioEvents();
        if (runtime.isIncrementalArrivalMode() && !runtime.getLateArrivalOrder().isEmpty()) {
            double lateStart = Config.MAX_SIMULATION_TIME * 0.40;
            if (runtime.getPlacementPolicy() == PlacementPolicy.DYNACOL_DCBO
                    && runtime.getFormationProtocol() != null) {
                DynaColArrivalScheduler.scheduleLateArrivals(
                        getId(), runtime.getLateArrivalOrder(), lateStart);
            } else if (runtime.getPlacementPolicy() == PlacementPolicy.STATIC_DCBO) {
                DynaColArrivalScheduler.scheduleLateArrivals(
                        getId(), runtime.getLateArrivalOrder(), lateStart);
            }
        } else if (runtime.getPlacementPolicy() == PlacementPolicy.DYNACOL_DCBO
                && runtime.getFormationProtocol() != null && !arrivalOrder.isEmpty()) {
            DynaColArrivalScheduler.scheduleArrivals(getId(), arrivalOrder, runtime.getFormationProtocol());
        }
        if (runtime.getPlacementPolicy() == PlacementPolicy.DYNACOL_DCBO
                && runtime.getFormationProtocol() != null) {
            send(getId(), DynaColConfig.GRT_BACKUP_REFRESH_MS, FogEvents.DYNACOL_GRT_REFRESH);
        }
    }

    private void scheduleScenarioEvents() {
        if (scenarioEventsScheduled) {
            return;
        }
        scenarioEventsScheduled = true;
        double duration = Config.MAX_SIMULATION_TIME;

        if ("churn".equals(scenarioKey)) {
            List<Integer> departable = new ArrayList<>();
            for (FogDevice device : getFogDevices()) {
                if (device.getName().startsWith("m-")) {
                    departable.add(device.getId());
                }
            }
            DynaColArrivalScheduler.scheduleChurnDepartures(getId(), departable, duration, scenarioSeed);
        }

        if ("fcm_failure".equals(scenarioKey) || "fcm".equals(scenarioKey)) {
            for (FogNodeState state : runtime.getNodeStates().values()) {
                if (state.isFcm() && state.getFcmDeviceId() != null) {
                    FogDevice fcm = getFogDeviceById(state.getFcmDeviceId());
                    if (fcm != null && fcm.getName().startsWith("d-")) {
                        DynaColArrivalScheduler.scheduleFcmFailure(getId(), fcm.getId(), duration);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case FogEvents.DYNACOL_NODE_ARRIVAL:
                handleNodeArrival((FogDevice) ev.getData());
                return;
            case FogEvents.DYNACOL_NODE_DEPARTURE:
                handleNodeDeparture((Integer) ev.getData());
                return;
            case FogEvents.DYNACOL_FCM_FAILURE:
                handleFcmFailure((Integer) ev.getData());
                return;
            case FogEvents.DYNACOL_GRT_REFRESH:
                handleGrtRefresh();
                return;
            case FogEvents.STOP_SIMULATION:
                super.processEvent(ev);
                printDynaColSummary();
                return;
            default:
                super.processEvent(ev);
        }
    }

    @Override
    protected void manageResources() {
        super.manageResources();
        syncColonyResourceTables();
        DynaColPlacementManager manager = runtime.getPlacementManager();
        if (manager != null) {
            manager.maybeReconcile(CloudSim.clock());
        }
    }

    private void syncColonyResourceTables() {
        if (!runtime.getFeatureFlags().isCrtEnabled()) {
            return;
        }
        for (FogNodeState state : runtime.getNodeStates().values()) {
            if (state.getCrt() == null) {
                continue;
            }
            state.getCrt().all().forEach(entry -> {
                FogDevice device = (FogDevice) CloudSim.getEntity(entry.getFogDeviceId());
                if (device != null) {
                    state.getCrt().updateAvailable(entry.getFogDeviceId(),
                            ResourceVector.availableFromFogDevice(device));
                }
            });
        }
    }

    private void handleNodeArrival(FogDevice node) {
        if (node == null) {
            return;
        }
        FogNodeState existing = runtime.getNodeStates().get(node.getId());
        if (existing != null && existing.getState() != NodeMembershipState.UNASSIGNED) {
            return;
        }
        ColonyFormationProtocol formation = runtime.getFormationProtocol();
        if (formation != null) {
            formation.createAndAddFogNode(node, CloudSim.clock());
            notifyGlobalRestructure();
            return;
        }
        if (runtime.getPlacementPolicy() == PlacementPolicy.STATIC_DCBO
                && runtime.getAllDevices() != null) {
            StaticClusterOverlayBuilder.joinLateNode(
                    runtime.getNodeStates(), runtime.getAllDevices(), node);
            notifyGlobalRestructure();
        }
    }

    private void handleNodeDeparture(int deviceId) {
        FogNodeState state = runtime.getNodeStates().get(deviceId);
        if (state == null) {
            return;
        }
        state.setState(NodeMembershipState.UNASSIGNED);
        ColonyFormationProtocol formation = runtime.getFormationProtocol();
        if (formation != null) {
            if (state.getFcmDeviceId() != null) {
                formation.markFcmDirty(state.getFcmDeviceId());
            }
            formation.refreshDirtyGrt(CloudSim.clock(), false);
        }
        notifyGlobalRestructure();
    }

    private void handleFcmFailure(int fcmDeviceId) {
        FogNodeState failed = runtime.getNodeStates().get(fcmDeviceId);
        if (failed == null || !failed.isFcm()) {
            return;
        }
        FogNodeState replacement = runtime.getNodeStates().values().stream()
                .filter(s -> s.getColonyId() != null && s.getColonyId().equals(failed.getColonyId()))
                .filter(s -> !s.isFcm() && s.getFogDeviceId() != fcmDeviceId)
                .filter(s -> {
                    FogDevice d = getFogDeviceById(s.getFogDeviceId());
                    return d != null && d.getName().startsWith("d-");
                })
                .findFirst()
                .orElse(null);
        if (replacement == null) {
            return;
        }

        failed.setState(NodeMembershipState.MEMBER);
        replacement.setState(NodeMembershipState.FCM);
        replacement.setFcmDeviceId(replacement.getFogDeviceId());
        Long colonyId = failed.getColonyId();
        for (FogNodeState member : runtime.getNodeStates().values()) {
            if (colonyId != null && colonyId.equals(member.getColonyId())) {
                member.setFcmDeviceId(replacement.getFogDeviceId());
            }
        }
        ControlOverheadMonitor.getInstance().recordHandover();
        ColonyFormationProtocol formation = runtime.getFormationProtocol();
        if (formation != null) {
            formation.markFcmDirty(replacement.getFogDeviceId());
            formation.markFcmDirty(fcmDeviceId);
            formation.refreshDirtyGrt(CloudSim.clock(), false);
        }
        notifyGlobalRestructure();
    }

    private void handleGrtRefresh() {
        ColonyFormationProtocol formation = runtime.getFormationProtocol();
        if (formation != null) {
            formation.refreshDirtyGrt(CloudSim.clock(), false);
        }
        send(getId(), DynaColConfig.GRT_BACKUP_REFRESH_MS, FogEvents.DYNACOL_GRT_REFRESH);
    }

    private void notifyGlobalRestructure() {
        DynaColPlacementManager manager = runtime.getPlacementManager();
        if (manager != null) {
            manager.onGlobalRestructure();
        }
    }

    private FogDevice getFogDeviceById(int id) {
        for (FogDevice device : getFogDevices()) {
            if (device.getId() == id) {
                return device;
            }
        }
        return null;
    }

    private void printDynaColSummary() {
        ControlOverheadMonitor monitor = ControlOverheadMonitor.getInstance();
        int fogNodes = 0;
        for (FogDevice device : getFogDevices()) {
            if (device.getParentId() != -1) {
                fogNodes++;
            }
        }
        System.out.println("[DynaCol] policy=" + runtime.getPlacementPolicy());
        System.out.println("[DynaCol] colonies=" + runtime.getNodeStates().values().stream()
                .filter(FogNodeState::isFcm).count());
        System.out.println("[DynaCol] control_messages=" + monitor.totalMessages()
                + " normalized=" + String.format("%.2f", monitor.normalizedOverhead(fogNodes)));
    }

    @Override
    public void submitApplication(Application app, ModulePlacement modulePlacement) {
        if (runtime.getPlacementManager() != null) {
            runtime.getPlacementManager().setApplication(app);
        }
        super.submitApplication(app, modulePlacement);
    }
}
