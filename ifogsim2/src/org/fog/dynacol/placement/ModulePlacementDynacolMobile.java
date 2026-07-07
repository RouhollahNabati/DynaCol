package org.fog.dynacol.placement;

import org.fog.application.Application;
import org.fog.dynacol.runtime.DynaColRuntime;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementMobileEdgewards;

import java.util.List;

/**
 * Mobile-aware DynaCol placement for mobility evaluation scenarios.
 */
public class ModulePlacementDynacolMobile extends ModulePlacementMobileEdgewards {

    public ModulePlacementDynacolMobile(List<FogDevice> fogDevices,
                                        List<Sensor> sensors,
                                        List<Actuator> actuators,
                                        Application application,
                                        ModuleMapping moduleMapping,
                                        DynaColRuntime runtime,
                                        double slaDeadlineMs) {
        super(fogDevices, sensors, actuators, application, ModulePlacementDynacol.enrichMapping(
                fogDevices, sensors, actuators, application, moduleMapping, runtime, slaDeadlineMs));
    }
}
