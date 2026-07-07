package org.fog.dynacol.placement;

import org.fog.dynacol.DynaColFeatureFlags;
import org.fog.dynacol.baseline.dogani.TwoTierDoganiPlacement;
import org.fog.dynacol.baseline.fogplan.FogPlanMinCostEngine;
import org.fog.dynacol.baseline.genetic.OfflineGeneticPlacement;
import org.fog.dynacol.baseline.tavousi.FuzzyTavousiPlacement;
import org.fog.dynacol.model.FogNodeState;

import java.util.List;
import java.util.Map;
import java.util.Random;

public enum PlacementPolicy {
    DYNACOL_DCBO,
    STATIC_DCBO,
    FOGPLAN_MIN_COST,
    EDGEWARD,
    GREEDY_NEAREST,
    TAVOUSI_FUZZY,
    DOGANI_TWOTIER,
    RANDOM_FEASIBLE,
    FOGPLAN_STYLE,
    OFFLINE_GA,
    GA_HC_STYLE,
    DRL_STYLE,
    MULTI_AGENT_STYLE,
    FOGPLAN_CENTRALIZED;

    public PlacementStrategy create(Map<Integer, FogNodeState> nodeStates,
                                    List<org.fog.entities.FogDevice> devices,
                                    Random random) {
        return create(nodeStates, devices, random, DynaColFeatureFlags.full());
    }

    public PlacementStrategy create(Map<Integer, FogNodeState> nodeStates,
                                    List<org.fog.entities.FogDevice> devices,
                                    Random random,
                                    DynaColFeatureFlags flags) {
        DynaColFeatureFlags effective = flags != null ? flags : DynaColFeatureFlags.full();
        switch (this) {
            case STATIC_DCBO:
                return new DCBOPlacement(nodeStates, devices, effective);
            case FOGPLAN_MIN_COST:
            case FOGPLAN_STYLE:
                return new FogPlanMinCostEngine(devices);
            case FOGPLAN_CENTRALIZED:
                return new FogPlanCentralizedPlacement(devices);
            case EDGEWARD:
                throw new UnsupportedOperationException("Edgeward uses ModulePlacementEdgewards directly");
            case GREEDY_NEAREST:
                return new GreedyNearestPlacement(devices);
            case TAVOUSI_FUZZY:
                return new FuzzyTavousiPlacement(devices);
            case DOGANI_TWOTIER:
                return new TwoTierDoganiPlacement(devices);
            case RANDOM_FEASIBLE:
                return new RandomFeasiblePlacement(devices, random);
            case OFFLINE_GA:
            case GA_HC_STYLE:
                return new OfflineGeneticPlacement(devices, random);
            case DRL_STYLE:
                return new DrlStylePlacement(devices, random);
            case MULTI_AGENT_STYLE:
                return new MultiAgentStylePlacement(devices);
            case DYNACOL_DCBO:
            default:
                return new DCBOPlacement(nodeStates, devices, effective);
        }
    }
}
