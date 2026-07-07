package org.fog.dynacol.table;

import org.fog.dynacol.model.ColonySummary;
import org.fog.dynacol.model.ResourceVector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Neighbor/Global Resource Table (GRT): summarized neighbor-colony awareness.
 */
public class GlobalResourceTable {

    private final Map<Long, ColonySummary> colonies = new HashMap<>();

    public void put(ColonySummary summary) {
        colonies.put(summary.getColonyId(), summary);
    }

    public ColonySummary get(long colonyId) {
        return colonies.get(colonyId);
    }

    public Collection<ColonySummary> all() {
        return colonies.values();
    }

    public List<ColonySummary> asList() {
        return new ArrayList<>(colonies.values());
    }

    public void updateFromColony(long colonyId, int fcmDeviceId, ResourceVector aggregate, double rttMs) {
        colonies.put(colonyId, new ColonySummary(colonyId, fcmDeviceId, aggregate, rttMs));
    }

    public void remove(long colonyId) {
        colonies.remove(colonyId);
    }
}
