package org.fog.dynacol.eval;

/**
 * Shared evaluation workload parameters (SLA deadlines, sensor periods).
 * Keep in sync with {@code evaluation/workload_spec.py}.
 */
public final class EvaluationWorkloadSpec {

    public static final double BASE_DEADLINE_MS = 60.0;
    public static final double N_SLOPE = 0.1;

    private static double nSlopeOverride = -1.0;

    private EvaluationWorkloadSpec() {
    }

    public static void applyNSlopeOverride(double nSlope) {
        nSlopeOverride = nSlope;
    }

    public static void resetOverrides() {
        nSlopeOverride = -1.0;
    }

    private static double effectiveNSlope() {
        return nSlopeOverride > 0.0 ? nSlopeOverride : N_SLOPE;
    }

    /** Base end-to-end SLA deadline before scenario scaling: {@code 60 + N*slope} ms. */
    public static double baseDeadlineMs(int targetNodes) {
        return BASE_DEADLINE_MS + targetNodes * effectiveNSlope();
    }

    /** Scenario-aware SLA deadline (ms) shared by all compared policies. */
    public static double slaDeadlineMs(String scenarioKey, int targetNodes) {
        double base = baseDeadlineMs(targetNodes);
        if (scenarioKey == null) {
            return base;
        }
        switch (scenarioKey) {
            case "burst":
            case "mobility_burst":
                return base * 0.85;
            case "mobility":
                return base * 0.90;
            case "churn":
                return base * 0.88;
            case "fcm_failure":
                return base * 0.82;
            default:
                return base;
        }
    }

    /** Camera sensor emission period (ms) for the load scenario. */
    public static double sensorPeriodMs(String scenarioKey) {
        if (scenarioKey == null) {
            return 5.0;
        }
        switch (scenarioKey) {
            case "burst":
            case "mobility+burst":
            case "mobility_burst":
                return 1.0;
            default:
                return 5.0;
        }
    }

    /** Apply sensitivity multiplier (e.g. 0.8, 1.0, 1.2) to a computed deadline. */
    public static double applyDeadlineScale(double deadlineMs, double scale) {
        if (scale <= 0.0) {
            return deadlineMs;
        }
        return deadlineMs * scale;
    }

    /** Variant label for SLA deadline sensitivity trials. */
    public static String slaDeadlineVariantLabel(double scale) {
        int pct = (int) Math.round(scale * 100.0);
        return "sla_m" + pct;
    }

    /** Variant label for N-slope sensitivity trials. */
    public static String nSlopeVariantLabel(double nSlope) {
        int pct = (int) Math.round(nSlope * 1000);
        return "sla_slope" + String.format("%03d", pct);
    }
}
