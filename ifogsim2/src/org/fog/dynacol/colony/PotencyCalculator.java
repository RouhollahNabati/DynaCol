package org.fog.dynacol.colony;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.model.ResourceVector;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.List;
import java.util.Map;

/**
 * Potency(f) = w1*R_cpu + w2*R_ram + w3*R_sto + w4*R_bw + w5*Q(f).
 */
public class PotencyCalculator {

    private final Map<Integer, FogDevice> deviceIndex;
    private final ResourceVector maxResources;

    public PotencyCalculator(List<FogDevice> devices) {
        this.deviceIndex = FogTopologyUtil.indexById(devices);
        this.maxResources = FogTopologyUtil.maxResources(devices);
    }

    public double potency(FogDevice device) {
        ResourceVector normalized = ResourceVector.normalize(
                ResourceVector.fromFogDevice(device),
                maxResources
        );
        double[] w = DynaColConfig.POTENCY_WEIGHTS;
        double q = connectivityQuality(device);
        return w[0] * normalized.getCpu()
                + w[1] * normalized.getRam()
                + w[2] * normalized.getStorage()
                + w[3] * normalized.getBandwidth()
                + w[4] * q;
    }

    private double connectivityQuality(FogDevice device) {
        double cloudRtt = FogTopologyUtil.nearestCloudRtt(device, List.copyOf(deviceIndex.values()), deviceIndex);
        double invCloud = 1.0 / Math.max(1.0, cloudRtt);

        double neighborSum = 0.0;
        int count = 0;
        for (FogDevice peer : deviceIndex.values()) {
            if (peer.getId() == device.getId()) {
                continue;
            }
            double rtt = FogTopologyUtil.estimateRttMs(device, peer, deviceIndex);
            if (rtt <= DynaColConfig.maxColonyRttMs()) {
                neighborSum += 1.0 / Math.max(1.0, rtt);
                count++;
            }
        }
        double avgNeighbor = count > 0 ? neighborSum / count : 0.0;
        return Math.min(1.0, 0.6 * invCloud + 0.4 * avgNeighbor);
    }
}
