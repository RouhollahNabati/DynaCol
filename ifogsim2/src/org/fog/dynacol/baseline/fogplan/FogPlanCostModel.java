package org.fog.dynacol.baseline.fogplan;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

/**
 * Cost terms from FogPlan-simulator {@code Components.Cost}.
 */
public final class FogPlanCostModel {

    private static final double CLOUD_UNIT_PROC = 0.002;
    private static final double FOG_UNIT_PROC_BASE = 0.002;
    private static final double CLOUD_UNIT_STOR = 4.0e-12;
    private static final double FOG_UNIT_STOR = 4.0e-12;
    private static final double FOG_CLOUD_COMM = 2.0e-10;
    private static final double FOG_CONTROLLER_COMM = 5.0e-10;
    private static final double SERVICE_PENALTY = 15.0;
    private static final double TAU_SECONDS = 10.0;

    private final FogPlanTopology topology;

    public FogPlanCostModel(FogPlanTopology topology) {
        this.topology = topology;
    }

    public double tauSeconds() {
        return TAU_SECONDS;
    }

    public double costExtraPc(double time, int cloudIndex, int serviceIndex, double extraTraffic, double lP) {
        return CLOUD_UNIT_PROC * lP * extraTraffic * time;
    }

    public double costPf(double time, int fogIndex, int serviceIndex, double lP, double lambdaIn) {
        return fogUnitProc(fogIndex) * lP * lambdaIn * time;
    }

    public double costExtraSc(double time, int cloudIndex, int serviceIndex, double lS, int[][] xp) {
        if (xp[serviceIndex][cloudIndex] == 1) {
            return 0;
        }
        return CLOUD_UNIT_STOR * lS * time;
    }

    public double costSf(double time, int fogIndex, int serviceIndex, double lS) {
        return FOG_UNIT_STOR * lS * time;
    }

    public double costCfc(double time, int fogIndex, int serviceIndex, double lambdaOut, int cloudIndex,
                          double lRp, double lRq) {
        return FOG_CLOUD_COMM * lambdaOut * (lRp + lRq) * time;
    }

    public double costDep(int fogIndex, int serviceIndex, double lS) {
        return lS * FOG_CONTROLLER_COMM;
    }

    public double costViol(double time, int serviceIndex, int fogIndex, double vPerNode,
                           double q, double lambdaIn) {
        return Math.max(0, vPerNode - (1.0 - q)) * lambdaIn * SERVICE_PENALTY * time;
    }

    private double fogUnitProc(int fogIndex) {
        FogDevice device = topology.fogHost(fogIndex);
        return FOG_UNIT_PROC_BASE * DynaColConfig.ratePerMipsForDevice(
                (long) device.getHost().getTotalMips(), device.getLevel());
    }
}
