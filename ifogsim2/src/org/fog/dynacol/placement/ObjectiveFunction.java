package org.fog.dynacol.placement;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.metrics.ControlOverheadMonitor;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.ColonySummary;
import org.fog.dynacol.model.ResourceVector;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.List;
import java.util.Map;

/**
 * Scalar objective J(s,x) = alpha*T + beta*E + gamma*Cost + delta*SLA + eta*Overhead.
 */
public class ObjectiveFunction {

    private final Map<Integer, FogDevice> deviceIndex;
    private final ResourceVector maxResources;
    private final int fogNodeCount;

    public ObjectiveFunction(List<FogDevice> devices) {
        this.deviceIndex = FogTopologyUtil.indexById(devices);
        this.maxResources = FogTopologyUtil.maxResources(devices);
        this.fogNodeCount = (int) devices.stream().filter(d -> d.getParentId() != -1).count();
    }

    public double score(ServiceRequest request, ColonyResourceEntry entry) {
        FogDevice target = deviceIndex.get(entry.getFogDeviceId());
        return evaluate(request, target, entry.getRttToFcmMs(), entry.getAvailable(), false);
    }

    public double score(ServiceRequest request, ColonySummary summary) {
        FogDevice target = deviceIndex.get(summary.getFcmDeviceId());
        return evaluate(request, target, summary.getRttToLocalFcmMs(), summary.getAggregateAvailable(), true);
    }

    private double evaluate(ServiceRequest request,
                            FogDevice target,
                            double networkRtt,
                            ResourceVector available,
                            boolean remoteColony) {
        if (target == null) {
            return Double.MAX_VALUE;
        }
        FogDevice source = deviceIndex.get(request.getSourceDeviceId());
        double latency = source == null ? networkRtt : FogTopologyUtil.estimateRttMs(source, target, deviceIndex);
        double energy = normalizedEnergy(target);
        double cost = normalizedCost(target);
        double slaPenalty = latency > request.getDeadlineMs() ? (latency - request.getDeadlineMs()) / request.getDeadlineMs() : 0.0;
        if (!available.canFit(request.getDemand())) {
            slaPenalty += 1.0;
        }
        double overheadNorm = overheadPenalty(remoteColony);
        return DynaColConfig.ALPHA_LATENCY * norm(latency, 200.0)
                + DynaColConfig.BETA_ENERGY * energy
                + DynaColConfig.GAMMA_COST * cost
                + DynaColConfig.DELTA_SLA * slaPenalty
                + DynaColConfig.ETA_OVERHEAD * overheadNorm;
    }

    private double overheadPenalty(boolean remoteColony) {
        double measured = ControlOverheadMonitor.getInstance().normalizedOverhead(Math.max(1, fogNodeCount));
        double normalized = Math.min(1.0, measured / DynaColConfig.OVERHEAD_NORM_DIVISOR);
        return normalized + (remoteColony ? DynaColConfig.REMOTE_COLONY_OVERHEAD_BONUS : 0.0);
    }

    private double normalizedEnergy(FogDevice device) {
        ResourceVector r = ResourceVector.normalize(ResourceVector.fromFogDevice(device), maxResources);
        return 1.0 - (0.4 * r.getCpu() + 0.3 * r.getRam() + 0.3 * r.getBandwidth());
    }

    private double normalizedCost(FogDevice device) {
        double rate = device.getRatePerMips();
        if (rate <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, rate / DynaColConfig.CLOUD_RATE_PER_MIPS);
    }

    private double norm(double value, double max) {
        return Math.min(1.0, value / max);
    }
}
