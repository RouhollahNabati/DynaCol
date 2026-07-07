package org.fog.dynacol.metrics;

/**
 * Aggregated metrics from one iFogSim2 trial (exported to evaluation CSV).
 */
public class SimulationMetrics {

    private final String method;
    private final String policy;
    private final int nodes;
    private final String scenario;
    private final int trial;
    private final long seed;
    private final double p95Ms;
    private final double meanLoopMs;
    private final double slaViolationPct;
    private final double overheadNorm;
    private final long controlMessages;
    private final int colonies;
    private final String variant;
    private final double meanEnergyPerRequest;
    private final double totalCost;

    public SimulationMetrics(String method, String policy, int nodes, String scenario, int trial, long seed,
                             double p95Ms, double meanLoopMs, double slaViolationPct,
                             double overheadNorm, long controlMessages, int colonies) {
        this(method, policy, nodes, scenario, trial, seed, p95Ms, meanLoopMs, slaViolationPct,
                overheadNorm, controlMessages, colonies, "", 0.0, 0.0);
    }

    public SimulationMetrics(String method, String policy, int nodes, String scenario, int trial, long seed,
                             double p95Ms, double meanLoopMs, double slaViolationPct,
                             double overheadNorm, long controlMessages, int colonies, String variant) {
        this(method, policy, nodes, scenario, trial, seed, p95Ms, meanLoopMs, slaViolationPct,
                overheadNorm, controlMessages, colonies, variant, 0.0, 0.0);
    }

    public SimulationMetrics(String method, String policy, int nodes, String scenario, int trial, long seed,
                             double p95Ms, double meanLoopMs, double slaViolationPct,
                             double overheadNorm, long controlMessages, int colonies, String variant,
                             double meanEnergyPerRequest, double totalCost) {
        this.method = method;
        this.policy = policy;
        this.nodes = nodes;
        this.scenario = scenario;
        this.trial = trial;
        this.seed = seed;
        this.p95Ms = p95Ms;
        this.meanLoopMs = meanLoopMs;
        this.slaViolationPct = slaViolationPct;
        this.overheadNorm = overheadNorm;
        this.controlMessages = controlMessages;
        this.colonies = colonies;
        this.variant = variant != null ? variant : "";
        this.meanEnergyPerRequest = meanEnergyPerRequest;
        this.totalCost = totalCost;
    }

    public String getMethod() {
        return method;
    }

    public String getPolicy() {
        return policy;
    }

    public int getNodes() {
        return nodes;
    }

    public String getScenario() {
        return scenario;
    }

    public int getTrial() {
        return trial;
    }

    public long getSeed() {
        return seed;
    }

    public double getP95Ms() {
        return p95Ms;
    }

    public double getMeanLoopMs() {
        return meanLoopMs;
    }

    public double getSlaViolationPct() {
        return slaViolationPct;
    }

    public double getOverheadNorm() {
        return overheadNorm;
    }

    public long getControlMessages() {
        return controlMessages;
    }

    public int getColonies() {
        return colonies;
    }

    public String getVariant() {
        return variant;
    }

    public double getMeanEnergyPerRequest() {
        return meanEnergyPerRequest;
    }

    public double getTotalCost() {
        return totalCost;
    }
}
