package org.fog.dynacol.runtime;

import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.dynacol.colony.ColonyFormationProtocol;
import org.fog.entities.FogDevice;
import org.fog.utils.FogEvents;

import java.util.List;

/**
 * Schedules cold-start node arrivals as CloudSim events (replay during simulation).
 */
public final class DynaColArrivalScheduler {

    private static final double ARRIVAL_INTERVAL_MS = 5.0;

    private DynaColArrivalScheduler() {
    }

    public static void scheduleLateArrivals(int controllerId,
                                            List<FogDevice> lateNodes,
                                            double startTimeMs) {
        double time = startTimeMs;
        for (FogDevice node : lateNodes) {
            if (node.getParentId() == -1) {
                continue;
            }
            CloudSim.send(controllerId, controllerId, time, FogEvents.DYNACOL_NODE_ARRIVAL, node);
            time += ARRIVAL_INTERVAL_MS;
        }
    }

    public static void scheduleArrivals(int controllerId,
                                        List<FogDevice> arrivalOrder,
                                        ColonyFormationProtocol formation) {
        double time = 0.0;
        for (FogDevice node : arrivalOrder) {
            if (node.getParentId() == -1) {
                continue;
            }
            CloudSim.send(controllerId, controllerId, time, FogEvents.DYNACOL_NODE_ARRIVAL, node);
            time += ARRIVAL_INTERVAL_MS;
        }
    }

    public static void scheduleChurnDepartures(int controllerId,
                                               List<Integer> deviceIds,
                                               double simDurationMs,
                                               long seed) {
        if (deviceIds.isEmpty()) {
            return;
        }
        java.util.Random rng = new java.util.Random(seed);
        double t1 = simDurationMs * 0.40;
        double t2 = simDurationMs * 0.70;
        int n = Math.max(1, deviceIds.size() / 10);
        for (int i = 0; i < n && i < deviceIds.size(); i++) {
            int idx = rng.nextInt(deviceIds.size());
            CloudSim.send(controllerId, controllerId, t1 + i * 2.0,
                    FogEvents.DYNACOL_NODE_DEPARTURE, deviceIds.get(idx));
        }
        for (int i = 0; i < n && i < deviceIds.size(); i++) {
            int idx = rng.nextInt(deviceIds.size());
            CloudSim.send(controllerId, controllerId, t2 + i * 2.0,
                    FogEvents.DYNACOL_NODE_DEPARTURE, deviceIds.get((idx + 1) % deviceIds.size()));
        }
    }

    public static void scheduleFcmFailure(int controllerId, int fcmDeviceId, double simDurationMs) {
        CloudSim.send(controllerId, controllerId, simDurationMs * 0.50,
                FogEvents.DYNACOL_FCM_FAILURE, fcmDeviceId);
    }
}
