package org.fog.dynacol.placement;

import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Shared helpers for filtering valid placement hosts in evaluation workloads.
 */
public final class PlacementCandidateUtil {

    private PlacementCandidateUtil() {
    }

    public static boolean isEdgeCamera(FogDevice device) {
        return device != null && device.getName().startsWith("m-");
    }

    public static boolean isCloud(FogDevice device) {
        return device != null && device.getParentId() == -1;
    }

    /** Area fog routers (d-*), excluding edge cameras, proxy, and cloud. */
    public static boolean isFogHost(FogDevice device) {
        return device != null && device.getName().startsWith("d-");
    }

    public static boolean requiresFogHost(ServiceRequest request) {
        String module = request.getModuleName();
        return module != null
                && !"motion_detector".equals(module)
                && !"user_interface".equals(module);
    }

    public static List<ColonyResourceEntry> fogCandidates(List<ColonyResourceEntry> entries,
                                                          Map<Integer, FogDevice> deviceIndex,
                                                          ServiceRequest request) {
        if (!requiresFogHost(request)) {
            return entries;
        }
        return entries.stream()
                .filter(entry -> {
                    FogDevice device = deviceIndex.get(entry.getFogDeviceId());
                    return isFogHost(device);
                })
                .collect(Collectors.toList());
    }

    /**
     * Keep only the top-K candidates by ascending score (lower is better).
     */
    public static <T> List<T> topKByScore(List<T> items, int k, ToDoubleFunction<T> scoreFn) {
        if (items == null || items.isEmpty() || k <= 0) {
            return new ArrayList<>();
        }
        if (items.size() <= k) {
            return new ArrayList<>(items);
        }
        return items.stream()
                .sorted(Comparator.comparingDouble(scoreFn))
                .limit(k)
                .collect(Collectors.toList());
    }
}
