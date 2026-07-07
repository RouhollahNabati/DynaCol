package org.fog.dynacol.baseline.fogplan;

import org.fog.dynacol.placement.PlacementCandidateUtil;
import org.fog.dynacol.util.FogTopologyUtil;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexes fog hosts (d-*) and cloud for the FogPlan Min-Cost engine (Yousefpour et al., IEEE IoT-J 2019).
 */
public final class FogPlanTopology {

    private final List<FogDevice> fogHosts;
    private final Map<Integer, Integer> deviceIdToFogIndex;
    private final Map<Integer, FogDevice> deviceIndex;
    private final FogDevice cloudDevice;
    private final int cloudIndex;

    public FogPlanTopology(List<FogDevice> allDevices) {
        this.deviceIndex = FogTopologyUtil.indexById(allDevices);
        List<FogDevice> hosts = new ArrayList<>();
        FogDevice cloud = null;
        for (FogDevice device : allDevices) {
            if (PlacementCandidateUtil.isCloud(device)) {
                cloud = device;
            } else if (PlacementCandidateUtil.isFogHost(device)) {
                hosts.add(device);
            }
        }
        hosts.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        this.fogHosts = Collections.unmodifiableList(hosts);
        this.cloudDevice = cloud;
        this.cloudIndex = 0;

        deviceIdToFogIndex = new HashMap<>();
        for (int j = 0; j < fogHosts.size(); j++) {
            deviceIdToFogIndex.put(fogHosts.get(j).getId(), j);
        }
    }

    public int numFogNodes() {
        return fogHosts.size();
    }

    public int numCloudServers() {
        return cloudDevice != null ? 1 : 0;
    }

    public FogDevice fogHost(int j) {
        return fogHosts.get(j);
    }

    public FogDevice getCloudDevice() {
        return cloudDevice;
    }

    public Map<Integer, FogDevice> getDeviceIndex() {
        return deviceIndex;
    }

    public Integer fogIndexForDeviceId(int deviceId) {
        return deviceIdToFogIndex.get(deviceId);
    }

    public int areaRouterFogIndex(FogDevice source) {
        if (source == null) {
            return -1;
        }
        FogDevice cur = source;
        while (cur != null && cur.getParentId() != -1) {
            if (PlacementCandidateUtil.isFogHost(cur)) {
                Integer idx = deviceIdToFogIndex.get(cur.getId());
                return idx != null ? idx : -1;
            }
            cur = deviceIndex.get(cur.getParentId());
        }
        return -1;
    }

    public int cloudServerForFog(int serviceIndex, int fogIndex) {
        return cloudIndex;
    }
}
