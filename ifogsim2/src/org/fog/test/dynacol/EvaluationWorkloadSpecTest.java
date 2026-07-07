package org.fog.test.dynacol;

import org.fog.dynacol.eval.EvaluationWorkloadSpec;

/**
 * Lightweight unit checks for {@link EvaluationWorkloadSpec} (run via main).
 */
public final class EvaluationWorkloadSpecTest {

    private EvaluationWorkloadSpecTest() {
    }

    public static void main(String[] args) {
        assertEq(EvaluationWorkloadSpec.baseDeadlineMs(500), 110.0, "base N=500");
        assertEq(EvaluationWorkloadSpec.slaDeadlineMs("normal", 500), 110.0, "normal N=500");
        assertEq(EvaluationWorkloadSpec.slaDeadlineMs("burst", 500), 93.5, "burst N=500");
        assertEq(EvaluationWorkloadSpec.slaDeadlineMs("churn", 500), 96.8, "churn N=500");
        assertEq(EvaluationWorkloadSpec.slaDeadlineMs("normal", 100), 70.0, "normal N=100");
        assertEq(EvaluationWorkloadSpec.sensorPeriodMs("burst"), 1.0, "burst period");
        assertEq(EvaluationWorkloadSpec.sensorPeriodMs("normal"), 5.0, "normal period");
        assertEq(EvaluationWorkloadSpec.applyDeadlineScale(110.0, 0.8), 88.0, "scale 0.8");
        assertStr(EvaluationWorkloadSpec.slaDeadlineVariantLabel(1.2), "sla_m120", "variant label");
        System.out.println("EvaluationWorkloadSpecTest: all passed");
    }

    private static void assertStr(String actual, String expected, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void assertEq(double actual, double expected, String label) {
        if (Math.abs(actual - expected) > 1e-9) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
