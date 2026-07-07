package org.fog.dynacol.baseline.fogplan;

import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

/**
 * Service delay model from FogPlan-simulator {@code Delay.calcServiceDelay} (M/M/c queueing).
 */
public final class FogPlanDelayModel {

    private static final int FOG_SERVERS = 4;
    private static final int CLOUD_SERVERS = 8;
    private static final double[] FACTORIAL = buildFactorial(20);

    private final FogPlanTopology topology;
    private final int numServices;
    private final int numFog;
    private final int numCloud;

    private final double[] kp;
    private final double[] kpp;
    private final double[][] rho;
    private final double[][] rhop;
    private final double[][] f;
    private final double[][] fp;
    private final double[][] p0;
    private final double[][] p0p;
    private final double[][] pq;
    private final double[][] pqp;

    public FogPlanDelayModel(FogPlanTopology topology, int numServices) {
        this.topology = topology;
        this.numServices = numServices;
        this.numFog = topology.numFogNodes();
        this.numCloud = Math.max(1, topology.numCloudServers());

        kp = new double[numFog];
        kpp = new double[numCloud];
        rho = new double[numServices][numFog];
        rhop = new double[numServices][numCloud];
        f = new double[numServices][numFog];
        fp = new double[numServices][numCloud];
        p0 = new double[numServices][numFog];
        p0p = new double[numServices][numCloud];
        pq = new double[numServices][numFog];
        pqp = new double[numServices][numCloud];

        for (int j = 0; j < numFog; j++) {
            kp[j] = Math.max(800.0, topology.fogHost(j).getHost().getTotalMips());
        }
        FogDevice cloud = topology.getCloudDevice();
        double cloudMips = cloud != null ? cloud.getHost().getTotalMips() : 44800.0;
        for (int k = 0; k < numCloud; k++) {
            kpp[k] = Math.max(16000.0, cloudMips);
        }
    }

    public double calcServiceDelay(int serviceIndex,
                                   int fogIndex,
                                   int[][] x,
                                   int[][] xp,
                                   double[][] arrivalInstructionsFog,
                                   double[][] arrivalInstructionsCloud,
                                   double lRp,
                                   double lRq) {
        FogDevice fog = topology.fogHost(fogIndex);
        int k = topology.cloudServerForFog(serviceIndex, fogIndex);
        FogDevice cloud = topology.getCloudDevice();
        if (x[serviceIndex][fogIndex] == 1) {
            double proc = calcProcTimeMmcFog(serviceIndex, fogIndex, x, arrivalInstructionsFog);
            double rIf = Math.max(1.0, fog.getUplinkBandwidth() / 1000.0);
            return (2.0 * propagationIotToFog(fog)) + proc + ((lRp + lRq) / rIf * 1000.0);
        }
        double proc = calcProcTimeMmcCloud(serviceIndex, k, xp, arrivalInstructionsCloud);
        double rIf = Math.max(1.0, fog.getUplinkBandwidth() / 1000.0);
        double rFc = cloud != null
                ? Math.max(1.0, cloud.getUplinkBandwidth() / 1000.0)
                : rIf;
        double dFc = cloud != null
                ? FogTopologyUtil.estimateRttMs(fog, cloud, topology.getDeviceIndex())
                : propagationIotToFog(fog) * 2;
        return (2.0 * (propagationIotToFog(fog) + dFc)) + proc
                + (((lRp + lRq) / rIf + (lRp + lRq) / rFc) * 1000.0);
    }

    private double calcProcTimeMmcFog(int a, int j, int[][] placement, double[][] arrivalFog) {
        initFog(a, j, placement, arrivalFog);
        if (f[a][j] == 0) {
            return Double.MAX_VALUE;
        }
        if (f[a][j] * kp[j] < arrivalFog[a][j]) {
            return 20.0;
        }
        return 1.0 / ((f[a][j] * kp[j]) / FOG_SERVERS)
                + pq[a][j] / (f[a][j] * kp[j] - arrivalFog[a][j]);
    }

    private double calcProcTimeMmcCloud(int a, int k, int[][] placement, double[][] arrivalCloud) {
        initCloud(a, k, placement, arrivalCloud);
        if (fp[a][k] == 0) {
            return Double.MAX_VALUE;
        }
        return 1.0 / ((fp[a][k] * kpp[k]) / CLOUD_SERVERS)
                + pqp[a][k] / (fp[a][k] * kpp[k] - arrivalCloud[a][k]);
    }

    private void initFog(int a, int j, int[][] x, double[][] arrivalFog) {
        f[a][j] = fogServiceFraction(x, a, j);
        if (x[a][j] != 0) {
            rho[a][j] = arrivalFog[a][j] / (f[a][j] * kp[j]);
        } else {
            rho[a][j] = Double.POSITIVE_INFINITY;
        }
        calcP0(a, j, FOG_SERVERS, rho, p0);
        calcPq(a, j, FOG_SERVERS, rho, p0, pq);
    }

    private void initCloud(int a, int k, int[][] xp, double[][] arrivalCloud) {
        fp[a][k] = cloudServiceFraction(xp, a, k);
        if (xp[a][k] != 0) {
            rhop[a][k] = arrivalCloud[a][k] / (fp[a][k] * kpp[k]);
        } else {
            rhop[a][k] = Double.POSITIVE_INFINITY;
        }
        calcP0(a, k, CLOUD_SERVERS, rhop, p0p);
        calcPq(a, k, CLOUD_SERVERS, rhop, p0p, pqp);
    }

    private static double fogServiceFraction(int[][] x, int a, int j) {
        double sum = 0;
        for (int s = 0; s < x.length; s++) {
            for (int n = 0; n < x[s].length; n++) {
                sum += x[s][n] * FogPlanMinCostEngine.defaultProcessingLoad(s);
            }
        }
        if (sum == 0) {
            return 0;
        }
        return (x[a][j] * FogPlanMinCostEngine.defaultProcessingLoad(a)) / sum;
    }

    private static double cloudServiceFraction(int[][] xp, int a, int k) {
        double sum = 0;
        for (int s = 0; s < xp.length; s++) {
            for (int n = 0; n < xp[s].length; n++) {
                sum += xp[s][n] * FogPlanMinCostEngine.defaultProcessingLoad(s);
            }
        }
        if (sum == 0) {
            return 0;
        }
        return (xp[a][k] * FogPlanMinCostEngine.defaultProcessingLoad(a)) / sum;
    }

    private static void calcP0(int a, int i, int servers, double[][] rho, double[][] p0Out) {
        if (rho[a][i] == Double.POSITIVE_INFINITY) {
            return;
        }
        double sum = 0;
        for (int c = 0; c <= servers - 1; c++) {
            sum += Math.pow(servers * rho[a][i], c) / FACTORIAL[c];
        }
        double d1 = Math.pow(servers * rho[a][i], servers) / FACTORIAL[servers];
        double d2 = 1.0 / (1.0 - rho[a][i]);
        p0Out[a][i] = 1.0 / (sum + d1 * d2);
    }

    private static void calcPq(int a, int i, int servers, double[][] rho, double[][] p0, double[][] pqOut) {
        if (rho[a][i] == Double.POSITIVE_INFINITY) {
            return;
        }
        double d1 = Math.pow(servers * rho[a][i], servers) / FACTORIAL[servers];
        double d2 = p0[a][i] / (1.0 - rho[a][i]);
        pqOut[a][i] = d1 * d2;
    }

    private static double propagationIotToFog(FogDevice fog) {
        return Math.max(1.0, fog.getUplinkLatency());
    }

    private static double[] buildFactorial(int n) {
        double[] fact = new double[n + 1];
        fact[0] = 1;
        for (int i = 1; i <= n; i++) {
            fact[i] = fact[i - 1] * i;
        }
        return fact;
    }
}
