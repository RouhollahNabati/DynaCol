package org.fog.dynacol.table;

import org.fog.dynacol.model.ColonyResourceEntry;
import org.fog.dynacol.model.ResourceVector;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Colony Resource Table (CRT): intra-colony resource visibility.
 */
public class ColonyResourceTable {

    private final Map<Integer, ColonyResourceEntry> entries = new HashMap<>();

    public void put(int fogDeviceId, ColonyResourceEntry entry) {
        entries.put(fogDeviceId, entry);
    }

    public ColonyResourceEntry get(int fogDeviceId) {
        return entries.get(fogDeviceId);
    }

    public Collection<ColonyResourceEntry> all() {
        return entries.values();
    }

    public List<ColonyResourceEntry> asList() {
        return new ArrayList<>(entries.values());
    }

    public static ColonyResourceTable initFromDevice(FogDevice device, double rttToSelf) {
        ColonyResourceTable crt = new ColonyResourceTable();
        ResourceVector resources = ResourceVector.fromFogDevice(device);
        crt.put(device.getId(), new ColonyResourceEntry(device.getId(), resources, rttToSelf));
        return crt;
    }

    public void mergeDevice(FogDevice device, double rttToFcmMs) {
        ResourceVector resources = ResourceVector.fromFogDevice(device);
        entries.put(device.getId(), new ColonyResourceEntry(device.getId(), resources, rttToFcmMs));
    }

    public void updateAvailable(int fogDeviceId, ResourceVector available) {
        ColonyResourceEntry entry = entries.get(fogDeviceId);
        if (entry != null) {
            entry.setAvailable(available);
        }
    }

    public ResourceVector aggregateAvailable() {
        double cpu = 0, ram = 0, sto = 0, bw = 0;
        for (ColonyResourceEntry entry : entries.values()) {
            cpu += entry.getAvailable().getCpu();
            ram += entry.getAvailable().getRam();
            sto += entry.getAvailable().getStorage();
            bw += entry.getAvailable().getBandwidth();
        }
        return new ResourceVector(cpu, ram, sto, bw);
    }
}
