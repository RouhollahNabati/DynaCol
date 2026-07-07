package org.fog.dynacol.baseline.fogplan;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps evaluation module names to FogPlan service indices (paper notation: service a).
 */
public final class FogPlanServiceIndex {

    private static final Map<String, Integer> MODULE_TO_INDEX = new HashMap<>();

    static {
        MODULE_TO_INDEX.put("object_detector", 0);
        MODULE_TO_INDEX.put("object_tracker", 1);
        MODULE_TO_INDEX.put("motion_detector", 2);
    }

    private FogPlanServiceIndex() {
    }

    public static int indexOf(String moduleName) {
        return MODULE_TO_INDEX.getOrDefault(moduleName, MODULE_TO_INDEX.size());
    }

    public static int numTrackedModules() {
        return MODULE_TO_INDEX.size();
    }
}
