package org.fog.dynacol.controller;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.runtime.DynaColRuntime;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.utils.Config;
import org.fog.utils.FogEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * DynaCol controller with lightweight camera mobility for evaluation scenarios.
 */
public class DynaColMobilityController extends DynaColController {

    private final Random mobilityRng;
    private final List<FogDevice> mobileCameras = new ArrayList<>();
    private final List<FogDevice> areaRouters = new ArrayList<>();

    public DynaColMobilityController(String name,
                                     List<FogDevice> fogDevices,
                                     List<Sensor> sensors,
                                     List<Actuator> actuators,
                                     DynaColRuntime runtime,
                                     String scenarioKey,
                                     long scenarioSeed) {
        super(name, fogDevices, sensors, actuators, runtime, scenarioKey, scenarioSeed);
        this.mobilityRng = new Random(scenarioSeed);
        for (FogDevice device : fogDevices) {
            if (device.getName().startsWith("m-")) {
                mobileCameras.add(device);
            } else if (device.getName().startsWith("d-")) {
                areaRouters.add(device);
            }
        }
    }

    @Override
    public void startEntity() {
        super.startEntity();
        scheduleMobilityEvents();
    }

    private void scheduleMobilityEvents() {
        if (mobileCameras.isEmpty() || areaRouters.isEmpty()) {
            return;
        }
        double duration = Config.MAX_SIMULATION_TIME;
        int events = Math.min(20, mobileCameras.size());
        for (int i = 0; i < events; i++) {
            double time = (duration * 0.2) + mobilityRng.nextDouble() * (duration * 0.6);
            FogDevice camera = mobileCameras.get(mobilityRng.nextInt(mobileCameras.size()));
            send(getId(), time, FogEvents.MOBILITY_MANAGEMENT, camera);
        }
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == FogEvents.MOBILITY_MANAGEMENT) {
            processMobility((FogDevice) ev.getData());
            return;
        }
        super.processEvent(ev);
    }

    private void processMobility(FogDevice camera) {
        if (camera == null || areaRouters.isEmpty()) {
            return;
        }
        int currentArea = FogTopologyUtil.areaIndex(camera);
        FogDevice newParent = areaRouters.get(mobilityRng.nextInt(areaRouters.size()));
        int newArea = FogTopologyUtil.areaIndex(newParent);
        if (currentArea == newArea) {
            return;
        }
        FogDevice oldParent = null;
        for (FogDevice device : getFogDevices()) {
            if (device.getId() == camera.getParentId()) {
                oldParent = device;
                break;
            }
        }
        if (oldParent == null || oldParent.getId() == newParent.getId()) {
            return;
        }
        oldParent.removeChild(camera.getId());
        camera.setParentId(newParent.getId());
        newParent.addChild(camera.getId());
        newParent.getChildToLatencyMap().put(camera.getId(), camera.getUplinkLatency());
        ControlOverheadMonitor.getInstance().recordHandover();
    }
}
