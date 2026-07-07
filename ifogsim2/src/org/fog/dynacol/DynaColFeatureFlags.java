package org.fog.dynacol;

/**
 * Runtime toggles for DCBO ablation studies and baseline comparison modes.
 */
public final class DynaColFeatureFlags {

    private boolean colonyOverlayEnabled = true;
    private boolean handoverEnabled = true;
    private boolean crtEnabled = true;
    private boolean grtEnabled = true;
    private boolean learningEnabled = true;

    public static DynaColFeatureFlags full() {
        return new DynaColFeatureFlags();
    }

    public static DynaColFeatureFlags baselineFlat() {
        DynaColFeatureFlags flags = new DynaColFeatureFlags();
        flags.colonyOverlayEnabled = false;
        return flags;
    }

    public static DynaColFeatureFlags fromVariant(String raw) {
        if (raw == null || raw.isEmpty() || "full".equalsIgnoreCase(raw)) {
            return full();
        }
        DynaColFeatureFlags flags = full();
        switch (raw.toLowerCase().replace('_', '-')) {
            case "no-handover":
                flags.handoverEnabled = false;
                break;
            case "crt-only":
                flags.grtEnabled = false;
                break;
            case "grt-only":
                flags.crtEnabled = false;
                break;
            case "no-learning":
                flags.learningEnabled = false;
                break;
            default:
                break;
        }
        return flags;
    }

    public static String ablationMethodName(String variant) {
        switch (variant.toLowerCase().replace('_', '-')) {
            case "no-handover":
                return "No-Handover";
            case "crt-only":
                return "CRT-only";
            case "grt-only":
                return "GRT-only";
            case "no-learning":
                return "No-Learning";
            case "full":
            default:
                return "Full DCBO";
        }
    }

    public boolean isColonyOverlayEnabled() {
        return colonyOverlayEnabled;
    }

    public void setColonyOverlayEnabled(boolean colonyOverlayEnabled) {
        this.colonyOverlayEnabled = colonyOverlayEnabled;
    }

    public boolean isHandoverEnabled() {
        return handoverEnabled;
    }

    public void setHandoverEnabled(boolean handoverEnabled) {
        this.handoverEnabled = handoverEnabled;
    }

    public boolean isCrtEnabled() {
        return crtEnabled;
    }

    public void setCrtEnabled(boolean crtEnabled) {
        this.crtEnabled = crtEnabled;
    }

    public boolean isGrtEnabled() {
        return grtEnabled;
    }

    public void setGrtEnabled(boolean grtEnabled) {
        this.grtEnabled = grtEnabled;
    }

    public boolean isLearningEnabled() {
        return learningEnabled;
    }

    public void setLearningEnabled(boolean learningEnabled) {
        this.learningEnabled = learningEnabled;
    }
}
