package org.fog.dynacol.model;

/**
 * Service placement request s used by DCBO.
 */
public class ServiceRequest {

    private final String moduleName;
    private final ResourceVector demand;
    private final double deadlineMs;
    private final int sourceDeviceId;
    /** Request arrival rate (req/s) for FogPlan traffic model lambda_in. */
    private final double arrivalRateRps;

    public ServiceRequest(String moduleName, ResourceVector demand, double deadlineMs, int sourceDeviceId) {
        this(moduleName, demand, deadlineMs, sourceDeviceId, 200.0);
    }

    public ServiceRequest(String moduleName, ResourceVector demand, double deadlineMs,
                          int sourceDeviceId, double arrivalRateRps) {
        this.moduleName = moduleName;
        this.demand = demand;
        this.deadlineMs = deadlineMs;
        this.sourceDeviceId = sourceDeviceId;
        this.arrivalRateRps = arrivalRateRps > 0 ? arrivalRateRps : 200.0;
    }

    public String getModuleName() {
        return moduleName;
    }

    public ResourceVector getDemand() {
        return demand;
    }

    public double getDeadlineMs() {
        return deadlineMs;
    }

    public int getSourceDeviceId() {
        return sourceDeviceId;
    }

    public double getArrivalRateRps() {
        return arrivalRateRps;
    }
}
