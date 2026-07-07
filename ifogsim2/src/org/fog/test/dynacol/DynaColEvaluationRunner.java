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
import org.fog.dynacol.DynaColFeatureFlags;
import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.eval.EvaluationWorkloadSpec;
import org.fog.dynacol.controller.DynaColController;
import org.fog.dynacol.controller.DynaColMobilityController;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.metrics.SimulationCsvExporter;
import org.fog.dynacol.metrics.SimulationMetrics;
import org.fog.dynacol.metrics.SimulationMetricsCollector;
import org.fog.dynacol.placement.ModulePlacementDynacol;
import org.fog.dynacol.placement.ModulePlacementDynacolMobile;
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
import org.fog.placement.ModulePlacement;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMobileEdgewards;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.Config;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Batch evaluation entry point for exporting simulator metrics to CSV.
 *
 * Usage:
 *   java org.fog.test.dynacol.DynaColEvaluationRunner [policy] [nodes] [scenario] [trial] [seed]
 *       [csvPath] [variant] [wtMs] [maxColonyRttMs] [bootstrapFraction] [slaDeadlineScale] [nSlope]
 */
public class DynaColEvaluationRunner {

    private static final int CAMERAS_PER_AREA = 4;
    private static final Set<String> ABLATION_VARIANT_KEYS = new HashSet<>(Arrays.asList(
            "full", "no-handover", "crt-only", "grt-only", "no-learning"
    ));

    public static void main(String[] args) throws Exception {
        PlacementPolicy policy = parsePolicy(arg(args, 0, "dcbo"));
        int targetNodes = parseInt(arg(args, 1, "100"), 100);
        String scenarioKey = arg(args, 2, "normal").toLowerCase();
        int trial = parseInt(arg(args, 3, "1"), 1);
        long seed = parseLong(arg(args, 4, "20260628"), 20260628L);
        String csvArg = arg(args, 5, "");
        String variantArg = arg(args, 6, "");
        double wtMs = parseDouble(arg(args, 7, ""), -1.0);
        double maxColonyRttMs = parseDouble(arg(args, 8, ""), -1.0);
        double bootstrapFraction = parseDouble(arg(args, 9, ""), 1.0);
        double slaDeadlineScale = parseDouble(arg(args, 10, ""), 1.0);
        double nSlope = parseDouble(arg(args, 11, ""), -1.0);

        boolean incrementalScenario = isIncrementalArrivalScenario(scenarioKey);
        String loadScenarioKey = resolveLoadScenarioKey(scenarioKey);

        if (nSlope > 0) {
            EvaluationWorkloadSpec.applyNSlopeOverride(nSlope);
        }

        String scenario = scenarioLabel(scenarioKey, incrementalScenario);
        double sensorPeriod = EvaluationWorkloadSpec.sensorPeriodMs(loadScenarioKey);
        double slaDeadline = EvaluationWorkloadSpec.applyDeadlineScale(
                EvaluationWorkloadSpec.slaDeadlineMs(loadScenarioKey, targetNodes),
                slaDeadlineScale);
        TopologyScale scale = scaleTopology(targetNodes);

        String ablationVariant = normalizeAblationVariant(variantArg);
        boolean ablationRun = isAblationRun(variantArg);
        DynaColFeatureFlags flags = DynaColFeatureFlags.fromVariant(ablationRun ? ablationVariant : "full");
        if (ablationRun) {
            policy = PlacementPolicy.DYNACOL_DCBO;
        }

        if (wtMs > 0 || maxColonyRttMs > 0) {
            DynaColConfig.applyOverrides(
                    wtMs > 0 ? wtMs : DynaColConfig.WAIT_THRESHOLD_MS,
                    maxColonyRttMs > 0 ? maxColonyRttMs : DynaColConfig.MAX_COLONY_RTT_MS);
        }

        try {
            runTrial(policy, targetNodes, scenarioKey, loadScenarioKey, incrementalScenario,
                    trial, seed, csvArg, ablationRun ? ablationVariant : variantArg, ablationRun, flags,
                    sensorPeriod, slaDeadline, scale, bootstrapFraction);
        } finally {
            DynaColConfig.resetOverrides();
            EvaluationWorkloadSpec.resetOverrides();
        }
    }

    private static void runTrial(PlacementPolicy policy,
                                 int targetNodes,
                                 String scenarioKey,
                                 String loadScenarioKey,
                                 boolean incrementalScenario,
                                 int trial,
                                 long seed,
                                 String csvArg,
                                 String variantArg,
                                 boolean ablationRun,
                                 DynaColFeatureFlags flags,
                                 double sensorPeriod,
                                 double slaDeadline,
                                 TopologyScale scale,
                                 double bootstrapFraction) throws Exception {
        Log.disable();
        TimeKeeper.resetInstance();
        ControlOverheadMonitor.getInstance().reset();

        Config.MAX_SIMULATION_TIME = simulationHorizonMs(policy, targetNodes);

        List<FogDevice> fogDevices = new ArrayList<>();
        List<Sensor> sensors = new ArrayList<>();
        List<Actuator> actuators = new ArrayList<>();

        CloudSim.init(1, Calendar.getInstance(), false);
        FogBroker broker = new FogBroker("broker");
        String appId = "dynacol_eval";
        Application application = createApplication(appId, broker.getId());
        application.setUserId(broker.getId());

        createTopology(fogDevices, sensors, actuators, broker.getId(), appId, scale, sensorPeriod, seed);

        List<FogDevice> arrivalOrder = buildArrivalOrder(fogDevices);

        ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
        moduleMapping.addModuleToDevice("user_interface", "cloud");

        DynaColRuntime runtime;
        if (policy == PlacementPolicy.EDGEWARD) {
            runtime = new DynaColRuntime(new HashMap<>(), null, policy, flags);
            runtime.setSensorPeriodMs(sensorPeriod);
            for (FogDevice device : fogDevices) {
                if (device.getName().startsWith("m-")) {
                    moduleMapping.addModuleToDevice("motion_detector", device.getName());
                }
            }
            Controller edgewardController = isMobilityScenario(scenarioKey)
                    ? new DynaColMobilityController(
                    "edgeward-controller", fogDevices, sensors, actuators,
                    runtime, scenarioKey, seed + trial)
                    : new DynaColController(
                    "edgeward-controller", fogDevices, sensors, actuators,
                    runtime, scenarioKey, seed + trial);
            ModulePlacement edgewardPlacement = isMobilityScenario(scenarioKey)
                    ? new ModulePlacementMobileEdgewards(
                    fogDevices, sensors, actuators, application, moduleMapping)
                    : new ModulePlacementEdgewards(
                    fogDevices, sensors, actuators, application, moduleMapping);
            edgewardController.submitApplication(application, edgewardPlacement);
        } else {
            DynaColBootstrap bootstrap = new DynaColBootstrap(fogDevices, seed + trial);
            bootstrap.setPlacementPolicy(policy);
            bootstrap.setFeatureFlags(resolveBootstrapFlags(policy, flags));
            bootstrap.setSlaDeadlineMs(slaDeadline);
            if (incrementalScenario) {
                bootstrap.setBootstrapFraction(bootstrapFraction > 0 ? bootstrapFraction : 0.5);
            } else if (bootstrapFraction > 0 && bootstrapFraction < 1.0) {
                bootstrap.setBootstrapFraction(bootstrapFraction);
            }
            runtime = bootstrap.run(arrivalOrder);
            runtime.setSensorPeriodMs(sensorPeriod);
            if (runtime.getPlacementManager() != null) {
                runtime.getPlacementManager().setSlaDeadlineMs(slaDeadline);
            }
            ModulePlacement placement = isMobilityScenario(scenarioKey)
                    ? new ModulePlacementDynacolMobile(
                    fogDevices, sensors, actuators, application, moduleMapping, runtime, slaDeadline)
                    : new ModulePlacementDynacol(
                    fogDevices, sensors, actuators, application, moduleMapping, runtime, slaDeadline);
            DynaColController controller = createController(
                    fogDevices, sensors, actuators, runtime, loadScenarioKey, seed + trial);
            if (policy == PlacementPolicy.DYNACOL_DCBO && !runtime.isIncrementalArrivalMode()) {
                controller.setArrivalOrder(arrivalOrder);
            }
            controller.submitApplication(application, placement);
        }

        int fogNodeCount = countFogNodes(fogDevices);
        int colonies = (int) runtime.getNodeStates().values().stream().filter(s -> s.isFcm()).count();

        TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
        Controller.EXIT_ON_STOP = false;
        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        colonies = (int) runtime.getNodeStates().values().stream().filter(s -> s.isFcm()).count();

        String scenario = scenarioLabel(scenarioKey, incrementalScenario);
        String method = ablationRun ? DynaColFeatureFlags.ablationMethodName(variantArg) : methodName(policy);
        String policyLabel = ablationRun ? "DYNACOL_DCBO_" + variantArg.toUpperCase() : policy.name();
        String variantLabel = ablationRun ? variantArg : (variantArg != null ? variantArg : "");

        SimulationMetrics metrics = SimulationMetricsCollector.collect(
                method,
                policyLabel,
                targetNodes,
                scenario,
                trial,
                seed,
                fogNodeCount,
                colonies,
                slaDeadline,
                variantLabel,
                fogDevices
        );
        SimulationCsvExporter.printJson(metrics);
        if (!csvArg.isEmpty()) {
            Path csvPath = Paths.get(csvArg);
            SimulationCsvExporter.append(csvPath, metrics);
        }
    }

    private static DynaColController createController(List<FogDevice> fogDevices,
                                                      List<Sensor> sensors,
                                                      List<Actuator> actuators,
                                                      DynaColRuntime runtime,
                                                      String scenarioKey,
                                                      long scenarioSeed) {
        if (isMobilityScenario(scenarioKey)) {
            return new DynaColMobilityController(
                    "dynacol-controller", fogDevices, sensors, actuators, runtime, scenarioKey, scenarioSeed);
        }
        return new DynaColController(
                "dynacol-controller", fogDevices, sensors, actuators, runtime, scenarioKey, scenarioSeed);
    }

    private static DynaColFeatureFlags resolveBootstrapFlags(PlacementPolicy policy, DynaColFeatureFlags flags) {
        if (policy == PlacementPolicy.EDGEWARD) {
            return DynaColFeatureFlags.baselineFlat();
        }
        if (DynaColBootstrap.usesColonyOverlay(policy)) {
            return flags;
        }
        return DynaColFeatureFlags.baselineFlat();
    }

    private static boolean isAblationRun(String variantArg) {
        return ABLATION_VARIANT_KEYS.contains(normalizeAblationVariant(variantArg));
    }

    private static String normalizeAblationVariant(String variantArg) {
        if (variantArg == null) {
            return "";
        }
        return variantArg.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static boolean isMobilityScenario(String scenarioKey) {
        if (scenarioKey == null) {
            return false;
        }
        return "mobility".equals(scenarioKey)
                || "mobility_burst".equals(scenarioKey)
                || "mobility+burst".equals(scenarioKey)
                || (scenarioKey.startsWith("incremental") && scenarioKey.contains("mobility"));
    }

    private static int simulationHorizonMs(PlacementPolicy policy, int targetNodes) {
        int base = 1000 + targetNodes;
        if (policy == PlacementPolicy.EDGEWARD) {
            base = 2000 + targetNodes * 2;
        }
        return Math.min(5000, base);
    }

    static TopologyScale scaleTopology(int targetFogNodes) {
        int numAreas = Math.max(1, (targetFogNodes - 2) / (1 + CAMERAS_PER_AREA));
        int actual = 2 + numAreas * (1 + CAMERAS_PER_AREA);
        return new TopologyScale(numAreas, CAMERAS_PER_AREA, actual);
    }

    static void createTopology(List<FogDevice> fogDevices, List<Sensor> sensors, List<Actuator> actuators,
                               int userId, String appId, TopologyScale scale, double sensorPeriodMs, long seed) {
        FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0,
                DynaColConfig.ratePerMipsForDevice(44800, 0), 16 * 103, 16 * 83.25);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        FogDevice proxy = createFogDevice("proxy-server", 2800, 4000, 10000, 10000, 1,
                DynaColConfig.ratePerMipsForDevice(2800, 1), 107.339, 83.4333);
        proxy.setParentId(cloud.getId());
        proxy.setUplinkLatency(100);
        fogDevices.add(proxy);

        for (int i = 0; i < scale.numAreas; i++) {
            addArea(String.valueOf(i), userId, appId, proxy.getId(), scale.camerasPerArea,
                    sensorPeriodMs, fogDevices, sensors, actuators, seed);
        }
    }

    private static void addArea(String id, int userId, String appId, int parentId, int camerasPerArea,
                                double sensorPeriodMs, List<FogDevice> fogDevices,
                                List<Sensor> sensors, List<Actuator> actuators, long seed) {
        RouterSpec routerSpec = routerSpecForArea(Integer.parseInt(id), seed);
        FogDevice router = createFogDevice("d-" + id, routerSpec.mips, routerSpec.ram,
                10000, 10000, 1, DynaColConfig.ratePerMipsForDevice(routerSpec.mips, 1),
                107.339, 83.4333);
        router.setParentId(parentId);
        router.setUplinkLatency(routerSpec.uplinkLatencyMs);
        fogDevices.add(router);

        for (int i = 0; i < camerasPerArea; i++) {
            FogDevice camera = addCamera(id + "-" + i, userId, appId, router.getId(), sensorPeriodMs);
            camera.setUplinkLatency(2 + (i % 3));
            fogDevices.add(camera);
            sensors.add(createSensor(id + "-" + i, userId, appId, camera.getId(), sensorPeriodMs));
            actuators.add(createActuator(id + "-" + i, userId, appId, camera.getId()));
        }
    }

    static RouterSpec routerSpecForArea(int areaIndex, long seed) {
        int tier = (int) Math.floorMod(seed + areaIndex * 31L, 3L);
        switch (tier) {
            case 0:
                return new RouterSpec(2000, 2048, 4 + (areaIndex % 2));
            case 1:
                return new RouterSpec(4000, 4096, 6 + (areaIndex % 2));
            default:
                return new RouterSpec(8000, 8192, 3 + (areaIndex % 3));
        }
    }

    private static Sensor createSensor(String id, int userId, String appId, int gatewayId, double periodMs) {
        Sensor sensor = new Sensor("s-" + id, "CAMERA", userId, appId, new DeterministicDistribution(periodMs));
        sensor.setGatewayDeviceId(gatewayId);
        sensor.setLatency(1.0);
        return sensor;
    }

    private static Actuator createActuator(String id, int userId, String appId, int gatewayId) {
        Actuator actuator = new Actuator("ptz-" + id, userId, appId, "PTZ_CONTROL");
        actuator.setGatewayDeviceId(gatewayId);
        actuator.setLatency(1.0);
        return actuator;
    }

    private static FogDevice addCamera(String id, int userId, String appId, int parentId, double sensorPeriodMs) {
        FogDevice camera = createFogDevice("m-" + id, 500, 1000, 10000, 10000, 3,
                DynaColConfig.ratePerMipsForDevice(500, 3), 87.53, 82.44);
        camera.setParentId(parentId);
        return camera;
    }

    private static List<FogDevice> buildArrivalOrder(List<FogDevice> fogDevices) {
        List<FogDevice> order = new ArrayList<>();
        for (FogDevice device : fogDevices) {
            if (device.getParentId() != -1) {
                order.add(device);
            }
        }
        order.sort((a, b) -> Integer.compare(a.getLevel(), b.getLevel()));
        return order;
    }

    private static int countFogNodes(List<FogDevice> fogDevices) {
        int count = 0;
        for (FogDevice device : fogDevices) {
            if (device.getParentId() != -1) {
                count++;
            }
        }
        return count;
    }

    static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw,
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
        try {
            FogDevice fogDevice = new FogDevice(nodeName, characteristics,
                    new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
            fogDevice.setLevel(level);
            return fogDevice;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("serial")
    static Application createApplication(String appId, int userId) {
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

    static String methodName(PlacementPolicy policy) {
        switch (policy) {
            case STATIC_DCBO:
                return "Static-DCBO";
            case FOGPLAN_MIN_COST:
            case FOGPLAN_STYLE:
                return "FogPlan-MinCost";
            case EDGEWARD:
                return "Edgeward";
            case GREEDY_NEAREST:
                return "Greedy-Nearest";
            case TAVOUSI_FUZZY:
                return "Tavousi-Fuzzy";
            case DOGANI_TWOTIER:
                return "Dogani-TwoTier";
            case RANDOM_FEASIBLE:
                return "Random-Feasible";
            case OFFLINE_GA:
            case GA_HC_STYLE:
                return "Offline-GA";
            case DRL_STYLE:
                return "DRL-based";
            case MULTI_AGENT_STYLE:
                return "Distributed/multi-agent";
            case FOGPLAN_CENTRALIZED:
                return "FogPlan-centralized";
            case DYNACOL_DCBO:
            default:
                return "DynaCol/DCBO";
        }
    }

    static String scenarioLabel(String key) {
        return scenarioLabel(key, isIncrementalArrivalScenario(key));
    }

    static String scenarioLabel(String key, boolean incremental) {
        if (incremental) {
            if (key.contains("mobility")) {
                return key.contains("burst")
                        ? "Incremental Arrival (Mobility + Burst)"
                        : "Incremental Arrival (Mobility)";
            }
            if (key.contains("burst")) {
                return "Incremental Arrival (Burst)";
            }
            return "Incremental Arrival (Normal)";
        }
        switch (key) {
            case "burst":
                return "Burst Load";
            case "mobility":
                return "Mobility";
            case "mobility_burst":
            case "mobility+burst":
                return "Mobility + Burst";
            case "churn":
                return "Churn";
            case "fcm":
            case "fcm_failure":
                return "FCM Failure";
            default:
                return "Normal Load";
        }
    }

    private static boolean isIncrementalArrivalScenario(String scenarioKey) {
        return scenarioKey != null && scenarioKey.startsWith("incremental");
    }

    private static String resolveLoadScenarioKey(String scenarioKey) {
        if (scenarioKey == null) {
            return "normal";
        }
        if (scenarioKey.startsWith("incremental")) {
            if (scenarioKey.contains("mobility")) {
                return scenarioKey.contains("burst") ? "mobility_burst" : "mobility";
            }
            return scenarioKey.contains("burst") ? "burst" : "normal";
        }
        return scenarioKey;
    }

    static PlacementPolicy parsePolicy(String raw) {
        switch (raw.toLowerCase()) {
            case "greedy":
                return PlacementPolicy.GREEDY_NEAREST;
            case "random":
                return PlacementPolicy.RANDOM_FEASIBLE;
            case "fogplan":
            case "fogplan_style":
            case "fogplan-style":
                return PlacementPolicy.FOGPLAN_MIN_COST;
            case "static":
            case "static_dcbo":
            case "static-dcbo":
                return PlacementPolicy.STATIC_DCBO;
            case "edgeward":
            case "edge_ward":
                return PlacementPolicy.EDGEWARD;
            case "gahc":
            case "ga_hc":
            case "offline_ga":
                return PlacementPolicy.OFFLINE_GA;
            case "tavousi":
            case "tavousi_fuzzy":
                return PlacementPolicy.TAVOUSI_FUZZY;
            case "dogani":
            case "dogani_twotier":
                return PlacementPolicy.DOGANI_TWOTIER;
            case "drl":
                return PlacementPolicy.DRL_STYLE;
            case "multiagent":
            case "multi_agent":
                return PlacementPolicy.MULTI_AGENT_STYLE;
            case "fogplan_central":
            case "fogplan-central":
                return PlacementPolicy.FOGPLAN_CENTRALIZED;
            default:
                return PlacementPolicy.DYNACOL_DCBO;
        }
    }

    private static String arg(String[] args, int index, String defaultValue) {
        return index < args.length ? args[index] : defaultValue;
    }

    private static int parseInt(String raw, int defaultValue) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLong(String raw, long defaultValue) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double parseDouble(String raw, double defaultValue) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static final class TopologyScale {
        final int numAreas;
        final int camerasPerArea;
        final int actualFogNodes;

        TopologyScale(int numAreas, int camerasPerArea, int actualFogNodes) {
            this.numAreas = numAreas;
            this.camerasPerArea = camerasPerArea;
            this.actualFogNodes = actualFogNodes;
        }
    }

    static final class RouterSpec {
        final long mips;
        final int ram;
        final double uplinkLatencyMs;

        RouterSpec(long mips, int ram, double uplinkLatencyMs) {
            this.mips = mips;
            this.ram = ram;
            this.uplinkLatencyMs = uplinkLatencyMs;
        }
    }
}
