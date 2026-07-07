package org.fog.dynacol.baseline.fogplan;

/**
 * Associates fog node index with incoming traffic for Min-Cost / Min-Viol sorting
 * (FogPlan-simulator {@code Components.FogTrafficIndex}).
 */
public final class FogTrafficIndex implements Comparable<FogTrafficIndex> {

    private final int fogIndex;
    private final double traffic;
    private final boolean sortAscending;

    public FogTrafficIndex(int fogIndex, double traffic, boolean sortAscending) {
        this.fogIndex = fogIndex;
        this.traffic = traffic;
        this.sortAscending = sortAscending;
    }

    public int getFogIndex() {
        return fogIndex;
    }

    public double getTraffic() {
        return traffic;
    }

    @Override
    public int compareTo(FogTrafficIndex other) {
        if (sortAscending) {
            return Double.compare(this.traffic, other.traffic);
        }
        return Double.compare(other.traffic, this.traffic);
    }
}
