package org.fog.dynacol.metrics;

import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.fog.entities.FogDevice;
import org.fog.utils.TimeKeeper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class SimulationMetricsCollector {

    private SimulationMetricsCollector() {
    }

    public static SimulationMetrics collect(String method, String policy, int nodes, String scenario,
                                              int trial, long seed, int fogNodeCount, int colonies,
                                              double slaDeadlineMs) {
        return collect(method, policy, nodes, scenario, trial, seed, fogNodeCount, colonies,
                slaDeadlineMs, "", null);
    }

    public static SimulationMetrics collect(String method, String policy, int nodes, String scenario,
                                              int trial, long seed, int fogNodeCount, int colonies,
                                              double slaDeadlineMs, String variant) {
        return collect(method, policy, nodes, scenario, trial, seed, fogNodeCount, colonies,
                slaDeadlineMs, variant, null);
    }

    public static SimulationMetrics collect(String method, String policy, int nodes, String scenario,
                                              int trial, long seed, int fogNodeCount, int colonies,
                                              double slaDeadlineMs, String variant,
                                              List<FogDevice> fogDevices) {
        List<Double> primarySamples = primaryLoopSamples();
        double p95 = percentile(primarySamples, 95);
        double mean = mean(primarySamples);
        double slaPct = slaViolationRate(primarySamples, slaDeadlineMs);
        ControlOverheadMonitor monitor = ControlOverheadMonitor.getInstance();
        double overhead = monitor.normalizedOverhead(fogNodeCount);
        double totalEnergy = aggregateEnergy(fogDevices);
        double totalCost = aggregateCost(fogDevices);
        int requestCount = Math.max(1, primarySamples.size());
        double meanEnergyPerRequest = totalEnergy / requestCount;
        return new SimulationMetrics(
                method, policy, nodes, scenario, trial, seed,
                p95, mean, slaPct, overhead, monitor.totalMessages(), colonies, variant,
                meanEnergyPerRequest, totalCost
        );
    }

    private static double aggregateEnergy(List<FogDevice> fogDevices) {
        if (fogDevices == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (FogDevice device : fogDevices) {
            if (device.getParentId() != -1) {
                sum += device.getEnergyConsumption();
            }
        }
        return sum;
    }

    private static double aggregateCost(List<FogDevice> fogDevices) {
        if (fogDevices == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (FogDevice device : fogDevices) {
            sum += device.getTotalCost();
        }
        return sum;
    }

    /**
     * Main analytics loop in dynacol_eval is registered first (motion_detector →
     * object_detector → object_tracker). The shorter actuator loop completes more
     * often and must not dominate P95/SLA metrics.
     */
    private static List<Double> primaryLoopSamples() {
        Map<Integer, List<Double>> samples = TimeKeeper.getInstance().getLoopIdToLatencySamples();
        if (samples.isEmpty()) {
            return Collections.emptyList();
        }
        List<Double> primary = samples.get(1);
        if (primary != null && !primary.isEmpty()) {
            return new ArrayList<>(primary);
        }
        int bestLoopId = -1;
        double bestMean = -1.0;
        for (Map.Entry<Integer, List<Double>> entry : samples.entrySet()) {
            List<Double> list = entry.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            double loopMean = mean(list);
            if (loopMean > bestMean) {
                bestMean = loopMean;
                bestLoopId = entry.getKey();
            }
        }
        if (bestLoopId < 0) {
            return Collections.emptyList();
        }
        return new ArrayList<>(samples.get(bestLoopId));
    }

    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double[] arr = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i);
        }
        return new Percentile(p).evaluate(arr);
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static double slaViolationRate(List<Double> latencies, double deadlineMs) {
        if (latencies.isEmpty()) {
            return 0.0;
        }
        int violations = 0;
        for (double latency : latencies) {
            if (latency > deadlineMs) {
                violations++;
            }
        }
        return 100.0 * violations / latencies.size();
    }
}
