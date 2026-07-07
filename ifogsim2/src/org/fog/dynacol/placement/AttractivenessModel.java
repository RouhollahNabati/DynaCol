package org.fog.dynacol.placement;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.DynaColFeatureFlags;
import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.ColonySummary;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.table.ColonyResourceTable;
import org.fog.dynacol.table.GlobalResourceTable;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.Map;

/**
 * Adaptive attractiveness: A_{t+1}(x) = (1-rho)*A_t(x) + rho*Reward(s,x).
 */
public class AttractivenessModel {

    private final DynaColFeatureFlags flags;

    public AttractivenessModel() {
        this(DynaColFeatureFlags.full());
    }

    public AttractivenessModel(DynaColFeatureFlags flags) {
        this.flags = flags != null ? flags : DynaColFeatureFlags.full();
    }

    public void updateColonyEntry(ColonyResourceEntry entry, ServiceRequest request, double reward) {
        if (!flags.isLearningEnabled()) {
            return;
        }
        double prev = entry.getAttractiveness();
        entry.setAttractiveness((1.0 - DynaColConfig.RHO_REINFORCEMENT) * prev
                + DynaColConfig.RHO_REINFORCEMENT * reward);
    }

    public void updateColonySummary(ColonySummary summary, ServiceRequest request, double reward) {
        if (!flags.isLearningEnabled()) {
            return;
        }
        double prev = summary.getAttractiveness();
        summary.setAttractiveness((1.0 - DynaColConfig.RHO_REINFORCEMENT) * prev
                + DynaColConfig.RHO_REINFORCEMENT * reward);
    }

    public double reward(ServiceRequest request, FogDevice target, Map<Integer, FogDevice> deviceIndex) {
        FogDevice source = deviceIndex.get(request.getSourceDeviceId());
        double latency = source == null ? 50.0 : FogTopologyUtil.estimateRttMs(source, target, deviceIndex);
        double slaMargin = request.getDeadlineMs() - latency;
        return slaMargin > 0 ? Math.min(1.0, slaMargin / request.getDeadlineMs()) : 0.0;
    }

    public void applyTemporalDecay(ColonyResourceTable crt, GlobalResourceTable grt) {
        if (!flags.isLearningEnabled()) {
            return;
        }
        for (ColonyResourceEntry entry : crt.all()) {
            entry.setAttractiveness(entry.getAttractiveness() * (1.0 - DynaColConfig.LAMBDA_ATTRACTIVENESS));
        }
        for (ColonySummary summary : grt.all()) {
            summary.setAttractiveness(summary.getAttractiveness() * (1.0 - DynaColConfig.LAMBDA_COLONY));
        }
    }
}
