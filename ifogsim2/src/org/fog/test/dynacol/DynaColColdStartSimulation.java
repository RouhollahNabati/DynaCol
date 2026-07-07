package org.fog.test.dynacol;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.dynacol.controller.DynaColController;
import org.fog.dynacol.placement.ModulePlacementDynacol;
import org.fog.dynacol.placement.PlacementPolicy;
import org.fog.dynacol.runtime.DynaColBootstrap;
import org.fog.dynacol.runtime.DynaColRuntime;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.Config;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

/**
 * Cold-start DynaCol/DCBO evaluation scenario on iFogSim2 (DCNS-inspired topology).
 *
 * Usage:
 *   java org.fog.test.dynacol.DynaColColdStartSimulation [policy]
 * where policy is one of: dcbo, greedy, random, fogplan
 */
public class DynaColColdStartSimulation {

    private static final List<FogDevice> fogDevices = new ArrayList<>();
    private static final List<Sensor> sensors = new ArrayList<>();
    private static final List<Actuator> actuators = new ArrayList<>();
    private static final int NUM_AREAS = 1;
    private static final int CAMERAS_PER_AREA = 4;

    public static void main(String[] args) {
        PlacementPolicy policy = parsePolicy(args);
        Log.printLine("Starting DynaCol cold-start simulation with policy: " + policy);

        try {
            Log.disable();
            Config.MAX_SIMULATION_TIME = 500;
            CloudSim.init(1, Calendar.getInstance(), false);

            FogBroker broker = new FogBroker("broker");
            String appId = "dynacol_dcns";
            Application application = createApplication(appId, broker.getId());
            application.setUserId(broker.getId());

            createTopologyIncremental(broker.getId(), appId);

            List<FogDevice> arrivalOrder = buildArrivalOrder();
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            for (FogDevice device : fogDevices) {
                if (device.getName().startsWith("m-")) {
                    moduleMapping.addModuleToDevice("motion_detector", device.getName());
                }
            }
            moduleMapping.addModuleToDevice("user_interface", "cloud");

            if (policy == PlacementPolicy.EDGEWARD) {
                Controller controller = new Controller(
                        "edgeward-controller", fogDevices, sensors, actuators);
                controller.submitApplication(application,
                        new ModulePlacementEdgewards(
                                fogDevices, sensors, actuators, application, moduleMapping));
            } else {
                DynaColBootstrap bootstrap = new DynaColBootstrap(fogDevices, 20260628L);
                bootstrap.setPlacementPolicy(policy);
                bootstrap.setSlaDeadlineMs(40.0);
                DynaColRuntime runtime = bootstrap.run(arrivalOrder);
                runtime.setSensorPeriodMs(5.0);

                ModulePlacementDynacol placement = new ModulePlacementDynacol(
                        fogDevices, sensors, actuators, application, moduleMapping, runtime, 40.0);

                DynaColController controller = new DynaColController(
                        "dynacol-controller", fogDevices, sensors, actuators, runtime);
                if (policy == PlacementPolicy.DYNACOL_DCBO) {
                    controller.setArrivalOrder(arrivalOrder);
                }
                controller.submitApplication(application, placement);

                System.out.println("[DynaCol] module placements: " + placement.getModulePlacementDecisions());
                System.out.println("[DynaCol] colonies formed: "
                        + runtime.getNodeStates().values().stream().filter(s -> s.isFcm()).count());
            }

            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            Log.printLine("DynaCol simulation finished.");
        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("DynaCol simulation failed.");
        }
    }

    private static PlacementPolicy parsePolicy(String[] args) {
        if (args.length == 0) {
            return PlacementPolicy.DYNACOL_DCBO;
        }
        switch (args[0].toLowerCase()) {
            case "greedy":
                return PlacementPolicy.GREEDY_NEAREST;
            case "random":
                return PlacementPolicy.RANDOM_FEASIBLE;
            case "fogplan":
                return PlacementPolicy.FOGPLAN_MIN_COST;
            case "static":
            case "static_dcbo":
                return PlacementPolicy.STATIC_DCBO;
            case "edgeward":
                return PlacementPolicy.EDGEWARD;
            default:
                return PlacementPolicy.DYNACOL_DCBO;
        }
    }

    private static void createTopologyIncremental(int userId, String appId) {
        FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        FogDevice proxy = createFogDevice("proxy-server", 2800, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(100);
        fogDevices.add(proxy);

        for (int i = 0; i < NUM_AREAS; i++) {
            addArea(String.valueOf(i), userId, appId, proxy.getId());
        }
    }

    private static List<FogDevice> buildArrivalOrder() {
        List<FogDevice> order = new ArrayList<>();
        for (FogDevice device : fogDevices) {
            if (device.getParentId() != -1) {
                order.add(device);
            }
        }
        order.sort((a, b) -> Integer.compare(a.getLevel(), b.getLevel()));
        return order;
    }

    private static FogDevice addArea(String id, int userId, String appId, int parentId) {
        FogDevice router = createFogDevice("d-" + id, 2800, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
        router.setParentId(parentId);
        router.setUplinkLatency(2);
        fogDevices.add(router);

        for (int i = 0; i < CAMERAS_PER_AREA; i++) {
            FogDevice camera = addCamera(id + "-" + i, userId, appId, router.getId());
            camera.setUplinkLatency(2);
            fogDevices.add(camera);
        }
        return router;
    }

    private static FogDevice addCamera(String id, int userId, String appId, int parentId) {
        FogDevice camera = createFogDevice("m-" + id, 500, 1000, 10000, 10000, 3, 0, 87.53, 82.44);
        camera.setParentId(parentId);
        Sensor sensor = new Sensor("s-" + id, "CAMERA", userId, appId, new DeterministicDistribution(5));
        sensors.add(sensor);
        Actuator ptz = new Actuator("ptz-" + id, userId, appId, "PTZ_CONTROL");
        actuators.add(ptz);
        sensor.setGatewayDeviceId(camera.getId());
        sensor.setLatency(1.0);
        ptz.setGatewayDeviceId(camera.getId());
        ptz.setLatency(1.0);
        return camera;
    }

    private static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw,
                                             int level, double ratePerMips, double busyPower, double idlePower) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        PowerHost host = new PowerHost(
                FogUtils.generateEntityId(),
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(10000),
                1000000,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower)
        );

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);

        LinkedList<Storage> storageList = new LinkedList<>();
        FogDevice fogDevice;
        try {
            fogDevice = new FogDevice(nodeName, characteristics,
                    new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        fogDevice.setLevel(level);
        return fogDevice;
    }

    @SuppressWarnings("serial")
    private static Application createApplication(String appId, int userId) {
        Application application = Application.createApplication(appId, userId);

        application.addAppModule("object_detector", 10);
        application.addAppModule("motion_detector", 10);
        application.addAppModule("object_tracker", 10);
        application.addAppModule("user_interface", 10);

        application.addAppEdge("CAMERA", "motion_detector", 1000, 20000, "CAMERA", Tuple.UP, AppEdge.SENSOR);
        application.addAppEdge("motion_detector", "object_detector", 2000, 2000, "MOTION_VIDEO_STREAM", Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("object_detector", "user_interface", 500, 2000, "DETECTED_OBJECT", Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("object_detector", "object_tracker", 1000, 100, "OBJECT_LOCATION", Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("object_tracker", "PTZ_CONTROL", 100, 28, 100, "PTZ_PARAMS", Tuple.DOWN, AppEdge.ACTUATOR);

        application.addTupleMapping("motion_detector", "CAMERA", "MOTION_VIDEO_STREAM", new FractionalSelectivity(1.0));
        application.addTupleMapping("object_detector", "MOTION_VIDEO_STREAM", "OBJECT_LOCATION", new FractionalSelectivity(1.0));
        application.addTupleMapping("object_detector", "MOTION_VIDEO_STREAM", "DETECTED_OBJECT", new FractionalSelectivity(0.05));

        final AppLoop loop1 = new AppLoop(new ArrayList<String>() {{
            add("motion_detector");
            add("object_detector");
            add("object_tracker");
        }});
        final AppLoop loop2 = new AppLoop(new ArrayList<String>() {{
            add("object_tracker");
            add("PTZ_CONTROL");
        }});
        application.setLoops(new ArrayList<AppLoop>() {{
            add(loop1);
            add(loop2);
        }});
        return application;
    }
}
