package org.fog.dynacol.runtime;

import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.baseline.fogplan.FogPlanMinCostEngine;
import org.fog.dynacol.baseline.fogplan.FogPlanPlacementOps;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ResourceVector;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.placement.DCBOPlacement;
import org.fog.dynacol.placement.PlacementDepth;
import org.fog.dynacol.placement.PlacementPolicy;
import org.fog.dynacol.placement.PlacementStrategy;
import org.fog.entities.FogDevice;
import org.fog.utils.FogEvents;
import org.json.simple.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Event-driven runtime placement: L2 colony reconcile on triggers, L3 on topology events.
 */
public class DynaColPlacementManager {

    private static final double MIGRATION_DELAY_MS = 2.0;

    private final DynaColRuntime runtime;
    private final Map<Integer, FogDevice> deviceIndex;
    private final Map<Integer, Map<String, Integer>> areaModuleHosts = new HashMap<>();
    private final Map<Integer, AreaState> areaStates = new HashMap<>();

    private double lastReplacementMs = 0.0;
    private double lastFogPlanPeriodicMs = 0.0;
    private double reconcileIntervalMs = DynaColConfig.RECONCILE_MIN_INTERVAL_MS;
    private double slaDeadlineMs = 100.0;
    private boolean globalRestructurePending;
    private Application application;

    public DynaColPlacementManager(DynaColRuntime runtime, List<FogDevice> devices) {
        this.runtime = runtime;
        this.deviceIndex = new HashMap<>();
        for (FogDevice device : devices) {
            deviceIndex.put(device.getId(), device);
        }
    }

    public void setApplication(Application application) {
        this.application = application;
    }

    public void setSlaDeadlineMs(double slaDeadlineMs) {
        this.slaDeadlineMs = slaDeadlineMs;
    }

    public void recordInitialPlacement(int areaRouterId, String moduleName, int hostDeviceId) {
        areaModuleHosts.computeIfAbsent(areaRouterId, k -> new HashMap<>()).put(moduleName, hostDeviceId);
        areaStates.computeIfAbsent(areaRouterId, k -> new AreaState()).cameraCount = countCamerasForArea(areaRouterId);
    }

    /** L3: topology churn, FCM failure, or colony restructure. */
    public void onGlobalRestructure() {
        globalRestructurePending = true;
        reconcileIntervalMs = DynaColConfig.RECONCILE_MIN_INTERVAL_MS;
    }

    public void maybeReconcile(double nowMs) {
        if (application == null) {
            return;
        }
        PlacementPolicy policy = runtime.getPlacementPolicy();
        if (policy == PlacementPolicy.DYNACOL_DCBO || policy == PlacementPolicy.STATIC_DCBO) {
            maybeDcboReconcile(nowMs);
        } else if (policy == PlacementPolicy.FOGPLAN_MIN_COST
                || policy == PlacementPolicy.FOGPLAN_STYLE
                || policy == PlacementPolicy.FOGPLAN_CENTRALIZED) {
            maybeFogPlanReconcile(nowMs);
        }
    }

    private void maybeDcboReconcile(double nowMs) {
        PlacementStrategy strategy = runtime.getPlacementStrategy();
        if (!(strategy instanceof DCBOPlacement)) {
            return;
        }
        DCBOPlacement dcbo = (DCBOPlacement) strategy;

        if (globalRestructurePending) {
            globalRestructurePending = false;
            lastReplacementMs = nowMs;
            reconcileDcboAreas(dcbo, nowMs, PlacementDepth.L3_GLOBAL, true);
            growReconcileInterval();
            return;
        }

        if (nowMs - lastReplacementMs < reconcileIntervalMs) {
            return;
        }

        boolean anyTriggered = false;
        for (Integer areaRouterId : areaModuleHosts.keySet()) {
            if (shouldTriggerArea(areaRouterId, nowMs)) {
                anyTriggered = true;
                break;
            }
        }
        if (!anyTriggered) {
            return;
        }

        lastReplacementMs = nowMs;
        reconcileDcboAreas(dcbo, nowMs, PlacementDepth.L2_COLONY, false);
        growReconcileInterval();
    }

    private void reconcileDcboAreas(DCBOPlacement dcbo, double nowMs, PlacementDepth depth, boolean allAreas) {
        boolean migrated = false;
        for (Map.Entry<Integer, Map<String, Integer>> areaEntry : areaModuleHosts.entrySet()) {
            int areaRouterId = areaEntry.getKey();
            if (!allAreas && !shouldTriggerArea(areaRouterId, nowMs)) {
                continue;
            }
            if (reconcileArea(dcbo, areaRouterId, areaEntry.getValue(), depth, nowMs)) {
                migrated = true;
            }
            AreaState state = areaStates.computeIfAbsent(areaRouterId, id -> new AreaState());
            state.cameraCount = countCamerasForArea(areaRouterId);
            state.lastReconcileMs = nowMs;
        }
        if (migrated) {
            reconcileIntervalMs = DynaColConfig.RECONCILE_MIN_INTERVAL_MS;
        }
    }

    private boolean reconcileArea(DCBOPlacement dcbo,
                                  int areaRouterId,
                                  Map<String, Integer> modules,
                                  PlacementDepth depth,
                                  double nowMs) {
        FogNodeState localState = resolveFcmState(areaRouterId);
        if (localState == null) {
            return false;
        }
        AreaState areaState = areaStates.computeIfAbsent(areaRouterId, id -> new AreaState());
        int cameraCount = countCamerasForArea(areaRouterId);
        boolean migrated = false;

        for (Map.Entry<String, Integer> moduleEntry : modules.entrySet()) {
            String moduleName = moduleEntry.getKey();
            int currentHost = moduleEntry.getValue();
            if (nowMs - areaState.lastMigrationMs < DynaColConfig.MIGRATION_COOLDOWN_MS) {
                continue;
            }
            AppModule module = application.getModuleByName(moduleName);
            if (module == null) {
                continue;
            }
            ServiceRequest request = buildRequest(moduleName, module, areaRouterId, cameraCount);
            Optional<Integer> desired = dcbo.reconcileService(request, localState, currentHost, depth);
            if (!desired.isPresent() || desired.get() == currentHost) {
                continue;
            }
            FogDevice from = deviceIndex.get(currentHost);
            FogDevice to = deviceIndex.get(desired.get());
            if (from == null || to == null) {
                continue;
            }
            migrateModule(from, to, module);
            moduleEntry.setValue(desired.get());
            areaState.lastMigrationMs = nowMs;
            migrated = true;
        }
        return migrated;
    }

    private boolean shouldTriggerArea(int areaRouterId, double nowMs) {
        AreaState state = areaStates.computeIfAbsent(areaRouterId, id -> new AreaState());
        int cameras = countCamerasForArea(areaRouterId);
        if (state.cameraCount > 0) {
            double loadDelta = Math.abs(cameras - state.cameraCount) / (double) state.cameraCount;
            if (loadDelta >= DynaColConfig.LOAD_CHANGE_THRESHOLD) {
                return true;
            }
        } else if (cameras > 0) {
            return true;
        }

        if (hostUtilizationHigh(areaRouterId)) {
            return true;
        }

        if (slaPressure(areaRouterId)) {
            return true;
        }

        return nowMs - state.lastReconcileMs >= DynaColConfig.RECONCILE_MAX_INTERVAL_MS;
    }

    private boolean hostUtilizationHigh(int areaRouterId) {
        Map<String, Integer> modules = areaModuleHosts.get(areaRouterId);
        if (modules == null) {
            return false;
        }
        for (int hostId : modules.values()) {
            FogDevice host = deviceIndex.get(hostId);
            if (host == null || host.getHost() == null) {
                continue;
            }
            double total = host.getHost().getTotalMips();
            double avail = host.getHost().getAvailableMips();
            if (total > 0 && (total - avail) / total >= DynaColConfig.HOST_UTIL_HIGH) {
                return true;
            }
        }
        return false;
    }

    private boolean slaPressure(int areaRouterId) {
        Map<String, Integer> modules = areaModuleHosts.get(areaRouterId);
        if (modules == null) {
            return false;
        }
        for (Map.Entry<String, Integer> e : modules.entrySet()) {
            FogDevice source = deviceIndex.get(areaRouterId);
            FogDevice host = deviceIndex.get(e.getValue());
            if (source == null || host == null) {
                continue;
            }
            double rtt = org.fog.dynacol.util.FogTopologyUtil.estimateRttMs(source, host, deviceIndex);
            if (rtt > slaDeadlineMs * DynaColConfig.SLA_ESCALATE_HIGH_RATIO) {
                return true;
            }
        }
        return false;
    }

    private void growReconcileInterval() {
        reconcileIntervalMs = Math.min(
                DynaColConfig.RECONCILE_MAX_INTERVAL_MS,
                reconcileIntervalMs * 1.5);
    }

    private void maybeFogPlanReconcile(double nowMs) {
        if (nowMs - lastFogPlanPeriodicMs < FogPlanMinCostEngine.PERIODIC_TAU_MS) {
            return;
        }
        lastFogPlanPeriodicMs = nowMs;
        PlacementStrategy strategy = runtime.getPlacementStrategy();
        if (strategy instanceof FogPlanPlacementOps) {
            ControlOverheadMonitor.getInstance().recordAdvertise();
            ((FogPlanPlacementOps) strategy).runPeriodicOptimization();
        }
        reconcileFogPlan(strategy);
    }

    private void reconcileFogPlan(PlacementStrategy strategy) {
        for (Map.Entry<Integer, Map<String, Integer>> areaEntry : areaModuleHosts.entrySet()) {
            int areaRouterId = areaEntry.getKey();
            FogNodeState localState = resolveFcmState(areaRouterId);
            if (localState == null) {
                continue;
            }
            int cameraCount = countCamerasForArea(areaRouterId);
            for (Map.Entry<String, Integer> moduleEntry : areaEntry.getValue().entrySet()) {
                AppModule module = application.getModuleByName(moduleEntry.getKey());
                if (module == null) {
                    continue;
                }
                ServiceRequest request = buildRequest(moduleEntry.getKey(), module, areaRouterId, cameraCount);
                Optional<Integer> desired;
                if (strategy instanceof FogPlanPlacementOps) {
                    desired = ((FogPlanPlacementOps) strategy).resolveHost(request, localState);
                } else {
                    desired = strategy.placeService(request, localState);
                }
                applyMigration(areaRouterId, moduleEntry, module, desired);
            }
        }
    }

    private void applyMigration(int areaRouterId,
                                Map.Entry<String, Integer> moduleEntry,
                                AppModule module,
                                Optional<Integer> desired) {
        int currentHost = moduleEntry.getValue();
        if (!desired.isPresent() || desired.get() == currentHost) {
            return;
        }
        FogDevice from = deviceIndex.get(currentHost);
        FogDevice to = deviceIndex.get(desired.get());
        if (from == null || to == null) {
            return;
        }
        migrateModule(from, to, module);
        moduleEntry.setValue(desired.get());
    }

    private ServiceRequest buildRequest(String moduleName, AppModule module, int areaRouterId, int cameraCount) {
        double arrivalRateRps = (1000.0 / runtime.getSensorPeriodMs()) * cameraCount;
        return new ServiceRequest(
                moduleName,
                new ResourceVector(module.getMips(), module.getRam(),
                        Math.max(1000, module.getSize()), Math.max(1000, module.getBw())),
                slaDeadlineMs,
                areaRouterId,
                arrivalRateRps
        );
    }

    private int countCamerasForArea(int areaRouterId) {
        int count = 0;
        for (FogDevice device : deviceIndex.values()) {
            if (device.getName().startsWith("m-") && device.getParentId() == areaRouterId) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private void migrateModule(FogDevice from, FogDevice to, AppModule module) {
        JSONObject jsonSend = new JSONObject();
        jsonSend.put("module", module);
        jsonSend.put("delay", MIGRATION_DELAY_MS);

        JSONObject jsonReceive = new JSONObject();
        jsonReceive.put("module", module);
        jsonReceive.put("delay", MIGRATION_DELAY_MS);
        jsonReceive.put("application", application);

        CloudSim.send(from.getId(), from.getId(), MIGRATION_DELAY_MS, FogEvents.MODULE_SEND, jsonSend);
        CloudSim.send(to.getId(), to.getId(), MIGRATION_DELAY_MS, FogEvents.MODULE_RECEIVE, jsonReceive);
    }

    private FogNodeState resolveFcmState(int deviceId) {
        FogNodeState state = runtime.getNodeStates().get(deviceId);
        if (state == null) {
            return null;
        }
        if (!state.isFcm() && state.getFcmDeviceId() != null) {
            FogNodeState fcm = runtime.getNodeStates().get(state.getFcmDeviceId());
            if (fcm != null) {
                return fcm;
            }
        }
        return state;
    }

    private static final class AreaState {
        int cameraCount;
        double lastReconcileMs;
        double lastMigrationMs;
    }
}
