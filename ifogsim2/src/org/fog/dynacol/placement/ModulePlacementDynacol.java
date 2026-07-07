package org.fog.dynacol.placement;

import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ResourceVector;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.runtime.DynaColRuntime;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a {@link ModuleMapping} via DCBO/baseline policies, then delegates execution
 * to {@link ModulePlacementEdgewards} so iFogSim2 tuple routing stays consistent.
 */
public class ModulePlacementDynacol extends ModulePlacementEdgewards {

    private final Map<String, Integer> modulePlacementDecisions = new HashMap<>();

    public ModulePlacementDynacol(List<FogDevice> fogDevices,
                                  List<Sensor> sensors,
                                  List<Actuator> actuators,
                                  Application application,
                                  ModuleMapping moduleMapping,
                                  DynaColRuntime runtime,
                                  double slaDeadlineMs) {
        super(fogDevices, sensors, actuators, application, enrichMapping(
                fogDevices, sensors, actuators, application, moduleMapping, runtime, slaDeadlineMs));
        modulePlacementDecisions.putAll(extractDecisions(getModuleMapping(), fogDevices));
    }

    static ModuleMapping enrichMapping(List<FogDevice> fogDevices,
                                               List<Sensor> sensors,
                                               List<Actuator> actuators,
                                               Application application,
                                               ModuleMapping baseMapping,
                                               DynaColRuntime runtime,
                                               double slaDeadlineMs) {
        ModuleMapping mapping = cloneMapping(baseMapping);
        Set<String> fixed = mappedModules(mapping);
        Map<String, FogDevice> nameToDevice = new HashMap<>();
        Map<Integer, FogDevice> idToDevice = new HashMap<>();
        for (FogDevice device : fogDevices) {
            nameToDevice.put(device.getName(), device);
            idToDevice.put(device.getId(), device);
        }

        AppModule objectDetectorModule = application.getModuleByName("object_detector");
        AppModule objectTrackerModule = application.getModuleByName("object_tracker");

        for (AppModule module : application.getModules()) {
            String moduleName = module.getName();
            if ("client".equals(moduleName) || fixed.contains(moduleName)) {
                continue;
            }

            if ("motion_detector".equals(moduleName)) {
                for (FogDevice device : fogDevices) {
                    if (device.getName().startsWith("m-")) {
                        mapping.addModuleToDevice(moduleName, device.getName());
                    }
                }
            }
        }

        Map<Integer, AreaPlacementGroup> areaGroups = groupSensorsByAreaRouter(sensors, idToDevice);
        for (AreaPlacementGroup area : areaGroups.values()) {
            if (objectDetectorModule != null) {
                placeModuleForArea(mapping, nameToDevice, runtime, slaDeadlineMs,
                        objectDetectorModule, area, "object_detector");
            }
            if (objectTrackerModule != null) {
                placeModuleForArea(mapping, nameToDevice, runtime, slaDeadlineMs,
                        objectTrackerModule, area, "object_tracker");
            }
        }

        return mapping;
    }

    private static void placeModuleForArea(ModuleMapping mapping,
                                           Map<String, FogDevice> nameToDevice,
                                           DynaColRuntime runtime,
                                           double slaDeadlineMs,
                                           AppModule module,
                                           AreaPlacementGroup area,
                                           String moduleName) {
        ResourceVector demand = new ResourceVector(
                module.getMips(),
                module.getRam(),
                Math.max(1000, module.getSize()),
                Math.max(1000, module.getBw())
        );
        double arrivalRateRps = (1000.0 / Math.max(1.0, runtime.getSensorPeriodMs())) * Math.max(1, area.sensorCount);
        ServiceRequest request = new ServiceRequest(
                moduleName,
                demand,
                slaDeadlineMs,
                area.representativeSourceId,
                arrivalRateRps
        );

        FogNodeState localState = resolveFcmState(runtime, area.representativeSourceId);
        Optional<Integer> targetId = runtime.getPlacementStrategy().placeService(request, localState);
        FogDevice target = targetId
                .map(id -> nameToDevice.values().stream().filter(d -> d.getId() == id).findFirst().orElse(null))
                .orElse(area.areaRouter);

        if (target == null && area.areaRouter != null) {
            target = area.areaRouter;
        }
        if (target == null) {
            return;
        }

        mapping.addModuleToDevice(moduleName, target.getName());
        if (runtime.getPlacementManager() != null) {
            runtime.getPlacementManager().recordInitialPlacement(area.areaRouterId, moduleName, target.getId());
        }
    }

    private static Map<Integer, AreaPlacementGroup> groupSensorsByAreaRouter(List<Sensor> sensors,
                                                                             Map<Integer, FogDevice> idToDevice) {
        Map<Integer, AreaPlacementGroup> groups = new LinkedHashMap<>();
        for (Sensor sensor : sensors) {
            FogDevice gateway = idToDevice.get(sensor.getGatewayDeviceId());
            if (gateway == null) {
                continue;
            }
            FogDevice router = resolveAreaRouter(gateway, idToDevice);
            if (router == null) {
                continue;
            }
            AreaPlacementGroup group = groups.computeIfAbsent(router.getId(), id -> {
                AreaPlacementGroup g = new AreaPlacementGroup();
                g.areaRouterId = router.getId();
                g.areaRouter = router;
                g.representativeSourceId = sensor.getGatewayDeviceId();
                return g;
            });
            group.sensorCount++;
        }
        return groups;
    }

    private static FogDevice resolveAreaRouter(FogDevice gateway, Map<Integer, FogDevice> idToDevice) {
        FogDevice cur = gateway;
        while (cur != null && cur.getParentId() != -1) {
            if (cur.getName().startsWith("d-")) {
                return cur;
            }
            cur = idToDevice.get(cur.getParentId());
        }
        return null;
    }

    private static ModuleMapping cloneMapping(ModuleMapping baseMapping) {
        ModuleMapping mapping = ModuleMapping.createModuleMapping();
        for (Map.Entry<String, List<String>> entry : baseMapping.getModuleMapping().entrySet()) {
            for (String moduleName : entry.getValue()) {
                mapping.addModuleToDevice(moduleName, entry.getKey());
            }
        }
        return mapping;
    }

    private static Set<String> mappedModules(ModuleMapping mapping) {
        Set<String> modules = new HashSet<>();
        for (List<String> names : mapping.getModuleMapping().values()) {
            modules.addAll(names);
        }
        return modules;
    }

    private static Map<String, Integer> extractDecisions(ModuleMapping mapping, List<FogDevice> devices) {
        Map<String, Integer> decisions = new HashMap<>();
        Map<String, Integer> nameToId = new HashMap<>();
        for (FogDevice device : devices) {
            nameToId.put(device.getName(), device.getId());
        }
        for (Map.Entry<String, List<String>> entry : mapping.getModuleMapping().entrySet()) {
            Integer deviceId = nameToId.get(entry.getKey());
            if (deviceId == null) {
                continue;
            }
            for (String moduleName : entry.getValue()) {
                decisions.put(moduleName + "@" + entry.getKey(), deviceId);
            }
        }
        return decisions;
    }

    private static FogNodeState resolveFcmState(DynaColRuntime runtime, int sourceDeviceId) {
        FogNodeState state = runtime.getNodeStates().get(sourceDeviceId);
        if (state == null) {
            return runtime.getNodeStates().values().stream()
                    .filter(FogNodeState::isFcm)
                    .findFirst()
                    .orElse(null);
        }
        if (!state.isFcm() && state.getFcmDeviceId() != null) {
            FogNodeState fcm = runtime.getNodeStates().get(state.getFcmDeviceId());
            if (fcm != null) {
                return fcm;
            }
        }
        return state;
    }

    public Map<String, Integer> getModulePlacementDecisions() {
        return modulePlacementDecisions;
    }

    private static final class AreaPlacementGroup {
        int areaRouterId;
        FogDevice areaRouter;
        int representativeSourceId;
        int sensorCount;
    }
}
