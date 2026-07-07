package org.fog.dynacol.placement;

/**
 * Hierarchical placement depth: L1 local fast-path, L2 colony reconcile, L3 global/cloud.
 */
public enum PlacementDepth {
    L1_FAST,
    L2_COLONY,
    L3_GLOBAL
}
