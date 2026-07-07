package org.fog.dynacol.baseline.fogplan;

import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ServiceRequest;

import java.util.Optional;

/**
 * Periodic FogPlan FSC operations (Yousefpour et al., runFogDynamic every τ).
 */
public interface FogPlanPlacementOps {

    void registerTraffic(ServiceRequest request);

    void optimizeService(int serviceIndex);

    void runPeriodicOptimization();

    Optional<Integer> resolveHost(ServiceRequest request, FogNodeState localFcmState);
}
