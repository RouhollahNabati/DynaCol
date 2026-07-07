package org.fog.dynacol.model;

import org.cloudbus.cloudsim.power.PowerHost;
import org.fog.entities.FogDevice;

/**
 * Normalized resource snapshot R(f,t) = [cpu, ram, sto, bw].
 */
public class ResourceVector {

    private final double cpu;
    private final double ram;
    private final double storage;
    private final double bandwidth;

    public ResourceVector(double cpu, double ram, double storage, double bandwidth) {
        this.cpu = cpu;
        this.ram = ram;
        this.storage = storage;
        this.bandwidth = bandwidth;
    }

    public static ResourceVector fromFogDevice(FogDevice device) {
        double mips = device.getHostList().get(0).getTotalMips();
        double ram = device.getHostList().get(0).getRam();
        double storage = device.getHostList().get(0).getStorage();
        double bw = device.getHostList().get(0).getBw();
        return new ResourceVector(mips, ram, storage, bw);
    }

    public static ResourceVector availableFromFogDevice(FogDevice device) {
        PowerHost host = device.getHost();
        return new ResourceVector(
                host.getAvailableMips(),
                host.getRamProvisioner().getAvailableRam(),
                host.getStorage(),
                host.getBwProvisioner().getAvailableBw());
    }

    public static ResourceVector normalize(ResourceVector raw, ResourceVector max) {
        return new ResourceVector(
                safeDiv(raw.cpu, max.cpu),
                safeDiv(raw.ram, max.ram),
                safeDiv(raw.storage, max.storage),
                safeDiv(raw.bandwidth, max.bandwidth)
        );
    }

    private static double safeDiv(double a, double b) {
        return b <= 0.0 ? 0.0 : Math.min(1.0, a / b);
    }

    public boolean canFit(ResourceVector demand) {
        return cpu >= demand.cpu && ram >= demand.ram
                && storage >= demand.storage && bandwidth >= demand.bandwidth;
    }

    public ResourceVector subtract(ResourceVector demand) {
        return new ResourceVector(
                Math.max(0.0, cpu - demand.cpu),
                Math.max(0.0, ram - demand.ram),
                Math.max(0.0, storage - demand.storage),
                Math.max(0.0, bandwidth - demand.bandwidth)
        );
    }

    public double getCpu() {
        return cpu;
    }

    public double getRam() {
        return ram;
    }

    public double getStorage() {
        return storage;
    }

    public double getBandwidth() {
        return bandwidth;
    }
}
