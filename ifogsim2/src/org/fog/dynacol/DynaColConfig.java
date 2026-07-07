package org.fog.dynacol;

/**
 * Fixed policy parameters for DynaCol/DCBO (Table tab:init and tab:policy_params in the paper).
 */
public final class DynaColConfig {

    private DynaColConfig() {
    }

    // Cold-start colony formation (defaults; overridable per trial via applyOverrides)
    public static final double WAIT_THRESHOLD_MS = 20.0;
    public static final double ADVERTISE_PERIOD_MS = 10.0;
    public static final double MAX_COLONY_RTT_MS = 50.0;

    private static Double waitThresholdOverride;
    private static Double maxColonyRttOverride;

    public static void applyOverrides(double wtMs, double maxColonyRttMs) {
        waitThresholdOverride = wtMs;
        maxColonyRttOverride = maxColonyRttMs;
    }

    public static void resetOverrides() {
        waitThresholdOverride = null;
        maxColonyRttOverride = null;
    }

    public static double waitThresholdMs() {
        return waitThresholdOverride != null ? waitThresholdOverride : WAIT_THRESHOLD_MS;
    }

    public static double maxColonyRttMs() {
        return maxColonyRttOverride != null ? maxColonyRttOverride : MAX_COLONY_RTT_MS;
    }

    public static boolean hasMaxColonyRttOverride() {
        return maxColonyRttOverride != null;
    }

    /**
     * Derive max colony RTT from topology RTT spread and SLA when no trial override is set.
     * Formula: clamp(min(SLA × ratio, p95 × mult), floor, ceiling).
     */
    public static void autoTuneMaxColonyRttFromTopology(java.util.List<org.fog.entities.FogDevice> devices,
                                                        double slaDeadlineMs) {
        if (hasMaxColonyRttOverride() || devices == null || devices.isEmpty()) {
            return;
        }
        double p95 = org.fog.dynacol.util.FogTopologyUtil.pairwiseRttPercentile(devices, 95.0);
        double tuned = Math.min(slaDeadlineMs * AUTO_TUNE_MAX_RTT_SLA_RATIO,
                p95 * AUTO_TUNE_MAX_RTT_P95_MULT);
        tuned = Math.max(AUTO_TUNE_MAX_RTT_FLOOR_MS, Math.min(AUTO_TUNE_MAX_RTT_CEILING_MS, tuned));
        applyOverrides(waitThresholdMs(), tuned);
    }
    /** Added RTT per area-index difference between fog routers (limits cross-area colony merge). */
    public static final double AREA_RTT_PENALTY_MS = 18.0;
    public static final double NEAREST_CLOUD_RTT_MS = 70.0;

    // Manager handover
    public static final double HANDOVER_HYSTERESIS = 0.05;
    public static final double HANDOVER_COOLDOWN_MS = 100.0;

    // Potency weights (CPU, RAM, storage, bandwidth, connectivity)
    public static final double[] POTENCY_WEIGHTS = {0.25, 0.25, 0.20, 0.15, 0.15};

    // Tiered fog pricing (rate per MIPS) — used by placement cost terms and FogPlan-style baselines.
    public static final double CLOUD_RATE_PER_MIPS = 0.010;
    public static final double STRONG_FOG_RATE_PER_MIPS = 0.001;
    public static final double MEDIUM_FOG_RATE_PER_MIPS = 0.002;
    public static final double WEAK_FOG_RATE_PER_MIPS = 0.003;
    public static final double EDGE_FOG_RATE_PER_MIPS = 0.004;

    // Scalar objective J(s,x) weights (latency, energy, cost, SLA, overhead)
    public static final double ALPHA_LATENCY = 0.35;
    public static final double BETA_ENERGY = 0.15;
    public static final double GAMMA_COST = 0.15;
    public static final double DELTA_SLA = 0.25;
    public static final double ETA_OVERHEAD = 0.10;

    /** Normalize measured control-plane bytes to [0,1] for the objective overhead term. */
    public static final double OVERHEAD_NORM_DIVISOR = 200.0;
    public static final double REMOTE_COLONY_OVERHEAD_BONUS = 0.08;

    public static double ratePerMipsForDevice(long mips, int level) {
        if (level == 0) {
            return CLOUD_RATE_PER_MIPS;
        }
        if (mips >= 7000L) {
            return STRONG_FOG_RATE_PER_MIPS;
        }
        if (mips >= 3500L) {
            return MEDIUM_FOG_RATE_PER_MIPS;
        }
        if (mips >= 1500L) {
            return WEAK_FOG_RATE_PER_MIPS;
        }
        return EDGE_FOG_RATE_PER_MIPS;
    }

    // Attractiveness model
    public static final double RHO_REINFORCEMENT = 0.30;
    public static final double LAMBDA_ATTRACTIVENESS = 0.20;
    public static final double LAMBDA_COLONY = 0.15;

    public static final double DEFAULT_SLA_DEADLINE_MS = 100.0;

    // Hierarchical placement (OH-DCBO): L1 fast-path / L2 colony / L3 global
    /** Max CRT candidates scored per L2 local decision. */
    public static final int TOP_K_CRT_CANDIDATES = 5;
    /** Max remote colonies consulted per L2 decision. */
    public static final int TOP_K_GRT_COLONIES = 3;
    /** Stay on current host if latency estimate is below SLA × this factor. */
    public static final double SLA_STICKY_LOW_RATIO = 0.85;
    /** Escalate from L1 only if latency estimate exceeds SLA × this factor. */
    public static final double SLA_ESCALATE_HIGH_RATIO = 1.10;
    /** Keep current host if its score is within this fraction of the best L2 candidate. */
    public static final double STICKY_SCORE_MARGIN = 0.15;

    // Event-driven reconcile (replaces fixed 500ms polling)
    public static final double RECONCILE_MIN_INTERVAL_MS = 1500.0;
    public static final double RECONCILE_MAX_INTERVAL_MS = 8000.0;
    public static final double MIGRATION_COOLDOWN_MS = 1200.0;
    /** Relative camera-count change that triggers L2 reconcile for an area. */
    public static final double LOAD_CHANGE_THRESHOLD = 0.20;
    /** Host CPU utilisation above this triggers reconcile (0–1). */
    public static final double HOST_UTIL_HIGH = 0.82;
    /** Slow backup GRT refresh when topology is stable. */
    public static final double GRT_BACKUP_REFRESH_MS = 15000.0;

    // Multi-criteria colony selection during formation (lower composite score is better)
    public static final double FORMATION_W_RTT = 0.35;
    public static final double FORMATION_W_HEADROOM = 0.30;
    public static final double FORMATION_W_POTENCY = 0.20;
    public static final double FORMATION_W_BALANCE = 0.15;

    // Post-bootstrap consolidation
    /** Colonies with fewer CRT members than this are merged into a nearby stronger FCM. */
    public static final int CONSOLIDATE_MIN_COLONY_MEMBERS = 3;
    public static final int CONSOLIDATE_MAX_PASSES = 10;
    /** Hysteresis bypassed during bootstrap consolidation (potency tie-break only). */
    public static final double CONSOLIDATE_MERGE_HYSTERESIS = 0.0;

    // Auto-tune maxColonyRtt from measured topology RTTs
    public static final double AUTO_TUNE_MAX_RTT_SLA_RATIO = 0.5;
    public static final double AUTO_TUNE_MAX_RTT_P95_MULT = 2.0;
    public static final double AUTO_TUNE_MAX_RTT_FLOOR_MS = 30.0;
    public static final double AUTO_TUNE_MAX_RTT_CEILING_MS = 80.0;

    // Event-driven colony formation (cold-start overhead reduction)
    /** Max FCM advertisements per joining-node query. */
    public static final int TOP_K_FORMATION_QUERIES = 4;
    /** Only consider FCMs in areas within this index distance. */
    public static final int FORMATION_AREA_RADIUS = 2;
    /** Run merge check every N node arrivals (not every arrival). */
    public static final int MERGE_CHECK_EVERY_N_NODES = 8;
}
