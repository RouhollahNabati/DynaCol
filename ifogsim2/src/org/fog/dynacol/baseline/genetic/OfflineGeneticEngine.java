package org.fog.dynacol.baseline.genetic;

import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.ResourceVector;
import org.fog.dynacol.model.ServiceRequest;
import org.fog.dynacol.placement.ObjectiveFunction;
import org.fog.dynacol.placement.PlacementCandidateUtil;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generational GA over module-to-host assignments (Talavera-inspired offline probe).
 */
public final class OfflineGeneticEngine {

    public static final int POPULATION_SIZE = 20;
    public static final int GENERATIONS = 15;
    public static final int TOURNAMENT_K = 3;
    public static final double CROSSOVER_RATE = 0.8;
    public static final double MUTATION_RATE = 0.1;

    private static final String[] MODULES = {
            "object_detector", "object_tracker"
    };

    private final List<Integer> fogHostIds;
    private final Map<Integer, FogDevice> deviceIndex;
    private final ObjectiveFunction objectiveFunction;
    private final List<ServiceRequest> sampleRequests;
    private final Random random;

    public OfflineGeneticEngine(List<FogDevice> devices,
                                List<ColonyResourceEntry> crtEntries,
                                Random random) {
        this.random = random;
        this.deviceIndex = org.fog.dynacol.util.FogTopologyUtil.indexById(devices);
        this.objectiveFunction = new ObjectiveFunction(devices);
        this.fogHostIds = new ArrayList<>();
        for (ColonyResourceEntry entry : crtEntries) {
            FogDevice device = deviceIndex.get(entry.getFogDeviceId());
            if (PlacementCandidateUtil.isFogHost(device)) {
                fogHostIds.add(entry.getFogDeviceId());
            }
        }
        if (fogHostIds.isEmpty()) {
            for (ColonyResourceEntry entry : crtEntries) {
                fogHostIds.add(entry.getFogDeviceId());
            }
        }
        this.sampleRequests = buildSampleRequests(devices);
    }

    /** Returns preferred fog device id per module name. */
    public Map<String, Integer> evolvePreferredHosts() {
        if (fogHostIds.isEmpty() || MODULES.length == 0) {
            return Map.of();
        }
        int[][] population = new int[POPULATION_SIZE][MODULES.length];
        double[] fitness = new double[POPULATION_SIZE];
        for (int i = 0; i < POPULATION_SIZE; i++) {
            for (int j = 0; j < MODULES.length; j++) {
                population[i][j] = fogHostIds.get(random.nextInt(fogHostIds.size()));
            }
            fitness[i] = evaluate(population[i]);
        }
        int[] best = Arrays.copyOf(population[0], MODULES.length);
        double bestFit = fitness[0];

        for (int gen = 0; gen < GENERATIONS; gen++) {
            int[][] next = new int[POPULATION_SIZE][MODULES.length];
            double[] nextFit = new double[POPULATION_SIZE];
            for (int i = 0; i < POPULATION_SIZE; i++) {
                int[] parent1 = tournamentSelect(population, fitness);
                int[] parent2 = tournamentSelect(population, fitness);
                int[] child = random.nextDouble() < CROSSOVER_RATE
                        ? uniformCrossover(parent1, parent2)
                        : Arrays.copyOf(parent1, MODULES.length);
                mutate(child);
                next[i] = child;
                nextFit[i] = evaluate(child);
                if (nextFit[i] < bestFit) {
                    bestFit = nextFit[i];
                    best = Arrays.copyOf(child, MODULES.length);
                }
            }
            population = next;
            fitness = nextFit;
        }

        java.util.HashMap<String, Integer> prefs = new java.util.HashMap<>();
        for (int j = 0; j < MODULES.length; j++) {
            prefs.put(MODULES[j], best[j]);
        }
        return prefs;
    }

    private int[] tournamentSelect(int[][] population, double[] fitness) {
        int bestIdx = random.nextInt(POPULATION_SIZE);
        for (int i = 1; i < TOURNAMENT_K; i++) {
            int idx = random.nextInt(POPULATION_SIZE);
            if (fitness[idx] < fitness[bestIdx]) {
                bestIdx = idx;
            }
        }
        return Arrays.copyOf(population[bestIdx], MODULES.length);
    }

    private int[] uniformCrossover(int[] a, int[] b) {
        int[] child = new int[MODULES.length];
        for (int i = 0; i < MODULES.length; i++) {
            child[i] = random.nextBoolean() ? a[i] : b[i];
        }
        return child;
    }

    private void mutate(int[] chromosome) {
        for (int i = 0; i < chromosome.length; i++) {
            if (random.nextDouble() < MUTATION_RATE) {
                chromosome[i] = fogHostIds.get(random.nextInt(fogHostIds.size()));
            }
        }
    }

    private double evaluate(int[] chromosome) {
        double total = 0.0;
        int count = 0;
        for (ServiceRequest request : sampleRequests) {
            String module = request.getModuleName();
            int hostIdx = moduleIndex(module);
            if (hostIdx < 0) {
                continue;
            }
            int deviceId = chromosome[hostIdx];
            FogDevice target = deviceIndex.get(deviceId);
            if (target == null) {
                total += 10.0;
                count++;
                continue;
            }
            ColonyResourceEntry entry = new ColonyResourceEntry(
                    deviceId, ResourceVector.fromFogDevice(target), 0.0);
            total += objectiveFunction.score(request, entry);
            count++;
        }
        return count == 0 ? Double.MAX_VALUE : total / count;
    }

    private int moduleIndex(String module) {
        for (int i = 0; i < MODULES.length; i++) {
            if (MODULES[i].equals(module)) {
                return i;
            }
        }
        return -1;
    }

    private List<ServiceRequest> buildSampleRequests(List<FogDevice> devices) {
        List<ServiceRequest> requests = new ArrayList<>();
        FogDevice source = devices.stream()
                .filter(PlacementCandidateUtil::isEdgeCamera)
                .findFirst()
                .orElse(devices.get(0));
        ResourceVector demand = new ResourceVector(50.0, 0.05, 0.01, 1.0);
        for (String module : MODULES) {
            for (int i = 0; i < 10; i++) {
                requests.add(new ServiceRequest(
                        module, demand, 110.0, source.getId()));
            }
        }
        return requests;
    }
}
