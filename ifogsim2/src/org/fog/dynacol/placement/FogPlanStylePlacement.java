package org.fog.dynacol.placement;

import org.fog.dynacol.baseline.fogplan.FogPlanMinCostEngine;
import org.fog.entities.FogDevice;

import java.util.List;

/**
 * @deprecated Use {@link FogPlanMinCostEngine} via {@link PlacementPolicy#FOGPLAN_MIN_COST}.
 */
@Deprecated
public class FogPlanStylePlacement extends FogPlanMinCostEngine {

    public FogPlanStylePlacement(List<FogDevice> devices) {
        super(devices);
    }
}
