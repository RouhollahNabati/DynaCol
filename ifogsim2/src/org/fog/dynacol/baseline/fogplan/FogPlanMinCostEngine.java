package org.fog.dynacol.baseline.fogplan;

import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.placement.PlacementCandidateUtil;
import org.fog.dynacol.placement.PlacementStrategy;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Faithful port of FogPlan Algorithm 2 (Min-Cost) from
 * ashkan-software/FogPlan-simulator {@code Components.Method#MinCost}.
 */
public class FogPlanMinCostEngine implements PlacementStrategy, FogPlanPlacementOps {

    /** FogPlan paper periodic optimization interval τ (seconds → ms in CloudSim clock). */
    public static final double PERIODIC_TAU_MS = 10_000.0;

    private final FogPlanTopology topology;
    private final FogPlanDelayModel delayModel;
    private final FogPlanCostModel costModel;
    private final int numServices;
    private final int numFog;
    private final int numCloud;

    private final int[][] x;
    private final int[][] xBackup;
    private final int[][] xp;
    private final double[][] d;
    private final double[] vPer;
    private final double[][] lambdaIn;
    private final double[][] lambdaOut;
    private final double[][] lambdaPIn;
    private final double[][] arrivalInstructionsFog;
    private final double[][] arrivalInstructionsCloud;
    private final double[] thresholdMs;
    private final double[] qos;
    private final double[] lP;
    private final double[] lS;
    private final double[] lM;
    private final double[] lRp;
    private final double[] lRq;
    private final double[] ks;
    private final double[] km;
    private final int[][] h;

    public FogPlanMinCostEngine(List<FogDevice> allDevices) {
        this.topology = new FogPlanTopology(allDevices);
        this.numServices = FogPlanServiceIndex.numTrackedModules();
        this.numFog = topology.numFogNodes();
        this.numCloud = Math.max(1, topology.numCloudServers());
        this.delayModel = new FogPlanDelayModel(topology, numServices);
        this.costModel = new FogPlanCostModel(topology);

        x = new int[numServices][numFog];
        xBackup = new int[numServices][numFog];
        xp = new int[numServices][numCloud];
        d = new double[numServices][numFog];
        vPer = new double[numServices];
        lambdaIn = new double[numServices][numFog];
        lambdaOut = new double[numServices][numFog];
        lambdaPIn = new double[numServices][numCloud];
        arrivalInstructionsFog = new double[numServices][numFog];
        arrivalInstructionsCloud = new double[numServices][numCloud];
        thresholdMs = new double[numServices];
        qos = new double[numServices];
        lP = new double[numServices];
        lS = new double[numServices];
        lM = new double[numServices];
        lRp = new double[numServices];
        lRq = new double[numServices];
        ks = new double[numFog];
        km = new double[numFog];
        h = new int[numServices][numFog];

        for (int a = 0; a < numServices; a++) {
            thresholdMs[a] = 100.0;
            qos[a] = 0.95;
            lP[a] = defaultProcessingLoad(a);
            lS[a] = 50.0 * 1_000_000.0 * 8.0;
            lM[a] = 2.0 * 1_000_000.0 * 8.0;
            lRp[a] = 15.0 * 1000.0 * 8.0;
            lRq[a] = 15.0 * 8.0;
            for (int j = 0; j < numFog; j++) {
                h[a][j] = topology.cloudServerForFog(a, j);
            }
        }
        for (int j = 0; j < numFog; j++) {
            FogDevice host = topology.fogHost(j);
            ks[j] = Math.max(host.getHost().getStorage(), 25.0 * 1_000_000_000.0 * 8.0);
            km[j] = Math.max(host.getHost().getRam() * 1024L * 1024L, 8.0 * 1_000_000_000.0 * 8.0);
        }
    }

    static double defaultProcessingLoad(int serviceIndex) {
        switch (serviceIndex) {
            case 0:
                return 100.0;
            case 1:
                return 100.0;
            default:
                return 50.0;
        }
    }

    @Override
    public Optional<Integer> placeService(ServiceRequest request, FogNodeState localFcmState) {
        if (localFcmState == null || numFog == 0) {
            return Optional.empty();
        }
        int serviceIndex = FogPlanServiceIndex.indexOf(request.getModuleName());
        if (serviceIndex >= numServices) {
            return Optional.empty();
        }
        ControlOverheadMonitor.getInstance().recordQuery();
        registerTraffic(request);
        optimizeService(serviceIndex);
        return resolveHost(request, localFcmState);
    }

    @Override
    public void registerTraffic(ServiceRequest request) {
        int serviceIndex = FogPlanServiceIndex.indexOf(request.getModuleName());
        if (serviceIndex < 0 || serviceIndex >= numServices) {
            return;
        }
        thresholdMs[serviceIndex] = request.getDeadlineMs();
        int fogIndex = topology.areaRouterFogIndex(
                topology.getDeviceIndex().get(request.getSourceDeviceId()));
        if (fogIndex >= 0) {
            lambdaIn[serviceIndex][fogIndex] += request.getArrivalRateRps();
        }
    }

    @Override
    public void optimizeService(int serviceIndex) {
        if (serviceIndex < 0 || serviceIndex >= numServices) {
            return;
        }
        backupPlacement(serviceIndex);
        minCost(serviceIndex);
    }

    @Override
    public void runPeriodicOptimization() {
        for (int a = 0; a < numServices; a++) {
            optimizeService(a);
        }
    }

    @Override
    public Optional<Integer> resolveHost(ServiceRequest request, FogNodeState localFcmState) {
        int serviceIndex = FogPlanServiceIndex.indexOf(request.getModuleName());
        if (serviceIndex < 0 || serviceIndex >= numServices) {
            return Optional.empty();
        }
        Map<Integer, FogDevice> deviceIndex = topology.getDeviceIndex();
        FogDevice source = deviceIndex.get(request.getSourceDeviceId());

        List<ColonyResourceEntry> localCandidates = PlacementCandidateUtil.fogCandidates(
                localFcmState.getCrt().asList(), deviceIndex, request);

        Optional<Integer> localDeployed = localCandidates.stream()
                .filter(entry -> {
                    Integer idx = topology.fogIndexForDeviceId(entry.getFogDeviceId());
                    return idx != null && x[serviceIndex][idx] == 1
                            && entry.getAvailable().canFit(request.getDemand());
                })
                .min(Comparator.comparingDouble(entry -> distance(source, entry, deviceIndex)))
                .map(entry -> {
                    reserve(entry, request);
                    ControlOverheadMonitor.getInstance().recordPlacement();
                    return entry.getFogDeviceId();
                });
        if (localDeployed.isPresent()) {
            return localDeployed;
        }

        Optional<Integer> anyDeployed = findAnyDeployedFog(serviceIndex, request, source, deviceIndex);
        if (anyDeployed.isPresent()) {
            return anyDeployed;
        }

        if (xp[serviceIndex][topology.cloudServerForFog(serviceIndex, 0)] == 1
                && topology.getCloudDevice() != null) {
            ControlOverheadMonitor.getInstance().recordPlacement();
            return Optional.of(topology.getCloudDevice().getId());
        }

        return Optional.empty();
    }

    private Optional<Integer> findAnyDeployedFog(int serviceIndex,
                                                 ServiceRequest request,
                                                 FogDevice source,
                                                 Map<Integer, FogDevice> deviceIndex) {
        List<Integer> deployed = new ArrayList<>();
        for (int j = 0; j < numFog; j++) {
            if (x[serviceIndex][j] == 1) {
                deployed.add(topology.fogHost(j).getId());
            }
        }
        return deployed.stream()
                .filter(id -> {
                    FogDevice device = deviceIndex.get(id);
                    return device != null;
                })
                .min(Comparator.comparingDouble(id -> FogTopologyUtil.estimateRttMs(
                        source, deviceIndex.get(id), deviceIndex)))
                .map(id -> {
                    ControlOverheadMonitor.getInstance().recordPlacement();
                    return id;
                });
    }

    private void minCost(int a) {
        calcViolation(a);
        List<FogTrafficIndex> fogTrafficIndex = fogIncomingTraffic(a, false);
        Collections.sort(fogTrafficIndex);

        int listIndex = -1;
        while (listIndex < numFog - 1) {
            listIndex++;
            int j = fogTrafficIndex.get(listIndex).getFogIndex();
            if (x[a][j] == 0 && fogHasFreeResources(j)) {
                if (deployMakesSense(a, j)) {
                    x[a][j] = 1;
                    placementUpdatedForService(a);
                    calcViolation(a);
                }
            }
        }

        listIndex = numFog;
        while (listIndex > 0) {
            listIndex--;
            int j = fogTrafficIndex.get(listIndex).getFogIndex();
            if (x[a][j] == 1) {
                if (releaseMakesSense(a, j)) {
                    releaseServiceSafelyFromFogNodes(a, j);
                    placementUpdatedForService(a);
                    calcViolation(a);
                }
            }
        }
        deployOrReleaseCloudService(a);
    }

    private boolean deployMakesSense(int a, int j) {
        double futureSavings = savingsIfNotDeployed(a, j);
        x[a][j] = 1;
        d[a][j] = delayModel.calcServiceDelay(a, j, x, xp,
                arrivalInstructionsFog, arrivalInstructionsCloud, lRp[a], lRq[a]);
        double futureCost = costIfDeployed(a, j);
        releaseServiceSafelyFromFogNodes(a, j);
        d[a][j] = delayModel.calcServiceDelay(a, j, x, xp,
                arrivalInstructionsFog, arrivalInstructionsCloud, lRp[a], lRq[a]);
        return futureSavings > futureCost;
    }

    private boolean releaseMakesSense(int a, int j) {
        double futureSavings = costModel.costPf(costModel.tauSeconds(), j, a, lP[a], lambdaIn[a][j])
                + costModel.costSf(costModel.tauSeconds(), j, a, lS[a])
                + costModel.costViol(costModel.tauSeconds(), a, j,
                violationPerNode(a, j), qos[a], lambdaIn[a][j]);

        releaseServiceSafelyFromFogNodes(a, j);
        d[a][j] = delayModel.calcServiceDelay(a, j, x, xp,
                arrivalInstructionsFog, arrivalInstructionsCloud, lRp[a], lRq[a]);
        double futureCost = savingsIfNotDeployed(a, j);
        x[a][j] = 1;
        d[a][j] = delayModel.calcServiceDelay(a, j, x, xp,
                arrivalInstructionsFog, arrivalInstructionsCloud, lRp[a], lRq[a]);
        return futureSavings > futureCost;
    }

    private double savingsIfNotDeployed(int a, int j) {
        double fogTrafficPct = fogTrafficPercentage(a, j);
        return costModel.costCfc(costModel.tauSeconds(), j, a, lambdaOut[a][j], h[a][j], lRp[a], lRq[a])
                + costModel.costExtraPc(costModel.tauSeconds(), h[a][j], a, lambdaOut[a][j], lP[a])
                + costModel.costExtraSc(costModel.tauSeconds(), h[a][j], a, lS[a], xp)
                + costModel.costViol(costModel.tauSeconds(), a, j,
                violationPerNode(a, j, fogTrafficPct), qos[a], lambdaIn[a][j]);
    }

    private double costIfDeployed(int a, int j) {
        double fogTrafficPct = fogTrafficPercentage(a, j);
        double deployCost = xBackup[a][j] == 0 ? costModel.costDep(j, a, lS[a]) : 0.0;
        return deployCost
                + costModel.costPf(costModel.tauSeconds(), j, a, lP[a], lambdaIn[a][j])
                + costModel.costSf(costModel.tauSeconds(), j, a, lS[a])
                + costModel.costViol(costModel.tauSeconds(), a, j,
                violationPerNode(a, j, fogTrafficPct), qos[a], lambdaIn[a][j]);
    }

    private void deployOrReleaseCloudService(int a) {
        calcArrivalRatesOfInstructions(a);
        for (int k = 0; k < numCloud; k++) {
            if (lambdaPIn[a][k] > 0) {
                xp[a][k] = 1;
            } else {
                xp[a][k] = 0;
            }
        }
    }

    private void releaseServiceSafelyFromFogNodes(int a, int j) {
        x[a][j] = 0;
        xp[a][h[a][j]] = 1;
    }

    private void placementUpdatedForService(int a) {
        calcArrivalRatesOfInstructions(a);
    }

    private void calcArrivalRatesOfInstructions(int a) {
        for (int j = 0; j < numFog; j++) {
            if (x[a][j] == 1) {
                arrivalInstructionsFog[a][j] = lambdaIn[a][j] * lP[a];
            } else {
                arrivalInstructionsFog[a][j] = 0;
                lambdaOut[a][j] = lambdaIn[a][j];
            }
        }
        for (int k = 0; k < numCloud; k++) {
            lambdaPIn[a][k] = 0;
            for (int j = 0; j < numFog; j++) {
                if (x[a][j] == 0) {
                    lambdaPIn[a][k] += lambdaOut[a][j];
                }
            }
            arrivalInstructionsCloud[a][k] = lambdaPIn[a][k] * lP[a];
        }
    }

    private void calcViolation(int a) {
        double sumNum = 0;
        double sumDen = 0;
        for (int j = 0; j < numFog; j++) {
            d[a][j] = delayModel.calcServiceDelay(a, j, x, xp,
                    arrivalInstructionsFog, arrivalInstructionsCloud, lRp[a], lRq[a]);
            double v = d[a][j] > thresholdMs[a] ? 1.0 : 0.0;
            sumNum += v * lambdaIn[a][j];
            sumDen += lambdaIn[a][j];
        }
        vPer[a] = sumDen == 0 ? 0 : sumNum / sumDen;
    }

    private double violationPerNode(int a, int j) {
        return violationPerNode(a, j, fogTrafficPercentage(a, j));
    }

    private double violationPerNode(int a, int j, double fogTrafficPct) {
        return d[a][j] > thresholdMs[a] ? fogTrafficPct : 0;
    }

    private double fogTrafficPercentage(int a, int j) {
        double den = 0;
        for (int fog = 0; fog < numFog; fog++) {
            den += lambdaIn[a][fog];
        }
        return den == 0 ? 0 : lambdaIn[a][j] / den;
    }

    private boolean fogHasFreeResources(int j) {
        double usedStorage = 0;
        double usedMemory = 0;
        for (int a = 0; a < numServices; a++) {
            if (x[a][j] == 1) {
                usedStorage += lS[a];
                usedMemory += lM[a];
            }
        }
        return usedStorage <= ks[j] && usedMemory <= km[j];
    }

    private List<FogTrafficIndex> fogIncomingTraffic(int a, boolean ascending) {
        List<FogTrafficIndex> list = new ArrayList<>();
        for (int j = 0; j < numFog; j++) {
            list.add(new FogTrafficIndex(j, lambdaIn[a][j], ascending));
        }
        return list;
    }

    private void backupPlacement(int a) {
        for (int j = 0; j < numFog; j++) {
            xBackup[a][j] = x[a][j];
        }
    }

    private void reserve(ColonyResourceEntry entry, ServiceRequest request) {
        entry.setAvailable(entry.getAvailable().subtract(request.getDemand()));
    }

    private double distance(FogDevice source, ColonyResourceEntry entry, Map<Integer, FogDevice> deviceIndex) {
        if (source == null) {
            return entry.getRttToFcmMs();
        }
        FogDevice target = deviceIndex.get(entry.getFogDeviceId());
        return FogTopologyUtil.estimateRttMs(source, target, deviceIndex);
    }
}
