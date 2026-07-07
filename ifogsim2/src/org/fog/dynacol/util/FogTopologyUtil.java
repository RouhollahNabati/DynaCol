package org.fog.dynacol.util;

import org.fog.dynacol.DynaColConfig;
import org.fog.dynacol.model.ResourceVector;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FogTopologyUtil {

    private FogTopologyUtil() {
    }

    public static Map<Integer, FogDevice> indexById(List<FogDevice> devices) {
        Map<Integer, FogDevice> map = new HashMap<>();
        for (FogDevice device : devices) {
            map.put(device.getId(), device);
        }
        return map;
    }

    public static double estimateRttMs(FogDevice from, FogDevice to, Map<Integer, FogDevice> devices) {
        if (from.getId() == to.getId()) {
            return 0.0;
        }
        double base = Math.abs(from.getLevel() - to.getLevel()) * 2.0
                + Math.abs(from.getUplinkLatency() - to.getUplinkLatency())
                + hopDistance(from, to, devices) * 2.0;
        int areaDelta = Math.abs(areaIndex(from) - areaIndex(to));
        if (areaDelta > 0) {
            base += areaDelta * DynaColConfig.AREA_RTT_PENALTY_MS;
        }
        return base;
    }

    /** Area index parsed from d-{n} or m-{n}-* device names; -1 if not area-scoped. */
    public static int areaIndex(FogDevice device) {
        if (device == null || device.getName() == null) {
            return -1;
        }
        String name = device.getName();
        if (name.startsWith("d-")) {
            try {
                return Integer.parseInt(name.substring(2));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        if (name.startsWith("m-")) {
            String rest = name.substring(2);
            int dash = rest.indexOf('-');
            if (dash > 0) {
                try {
                    return Integer.parseInt(rest.substring(0, dash));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static int hopDistance(FogDevice from, FogDevice to, Map<Integer, FogDevice> devices) {
        int fromDepth = depth(from, devices);
        int toDepth = depth(to, devices);
        FogDevice lcaFrom = ascend(from, Math.min(fromDepth, toDepth), devices);
        FogDevice lcaTo = ascend(to, Math.min(fromDepth, toDepth), devices);
        int hops = 0;
        while (lcaFrom.getId() != lcaTo.getId()) {
            lcaFrom = devices.get(lcaFrom.getParentId());
            lcaTo = devices.get(lcaTo.getParentId());
            hops += 2;
            if (lcaFrom == null || lcaTo == null) {
                break;
            }
        }
        return hops + Math.abs(fromDepth - toDepth);
    }

    private static int depth(FogDevice device, Map<Integer, FogDevice> devices) {
        int d = 0;
        FogDevice cur = device;
        while (cur != null && cur.getParentId() != -1) {
            d++;
            cur = devices.get(cur.getParentId());
        }
        return d;
    }

    private static FogDevice ascend(FogDevice device, int steps, Map<Integer, FogDevice> devices) {
        FogDevice cur = device;
        for (int i = 0; i < steps && cur != null && cur.getParentId() != -1; i++) {
            cur = devices.get(cur.getParentId());
        }
        return cur == null ? device : cur;
    }

    public static double nearestCloudRtt(FogDevice device, List<FogDevice> devices, Map<Integer, FogDevice> index) {
        FogDevice cloud = null;
        for (FogDevice d : devices) {
            if (d.getParentId() == -1) {
                cloud = d;
                break;
            }
        }
        if (cloud == null) {
            return DynaColConfig.NEAREST_CLOUD_RTT_MS;
        }
        return estimateRttMs(device, cloud, index);
    }

    /** Sample pairwise fog RTTs (capped) and return the given percentile in ms. */
    public static double pairwiseRttPercentile(List<FogDevice> devices, double percentile) {
        Map<Integer, FogDevice> index = indexById(devices);
        List<FogDevice> fogNodes = new ArrayList<>();
        for (FogDevice device : devices) {
            if (device.getParentId() != -1) {
                fogNodes.add(device);
            }
        }
        if (fogNodes.size() < 2) {
            return DynaColConfig.MAX_COLONY_RTT_MS;
        }

        List<Double> samples = new ArrayList<>();
        final int maxSamples = 2000;
        int n = fogNodes.size();
        int stride = Math.max(1, (n * (n - 1) / 2) / maxSamples);
        int pairIndex = 0;
        outer:
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (pairIndex++ % stride == 0) {
                    samples.add(estimateRttMs(fogNodes.get(i), fogNodes.get(j), index));
                    if (samples.size() >= maxSamples) {
                        break outer;
                    }
                }
            }
        }
        if (samples.isEmpty()) {
            return DynaColConfig.MAX_COLONY_RTT_MS;
        }
        samples.sort(Double::compareTo);
        int idx = (int) Math.ceil(percentile / 100.0 * samples.size()) - 1;
        idx = Math.max(0, Math.min(samples.size() - 1, idx));
        return samples.get(idx);
    }

    public static ResourceVector maxResources(List<FogDevice> devices) {
        double cpu = 1, ram = 1, sto = 1, bw = 1;
        for (FogDevice device : devices) {
            ResourceVector r = ResourceVector.fromFogDevice(device);
            cpu = Math.max(cpu, r.getCpu());
            ram = Math.max(ram, r.getRam());
            sto = Math.max(sto, r.getStorage());
            bw = Math.max(bw, r.getBandwidth());
        }
        return new ResourceVector(cpu, ram, sto, bw);
    }
}
