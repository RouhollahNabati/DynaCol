package org.fog.dynacol.placement;

import org.fog.dynacol.model.FogNodeState;
import org.fog.dynacol.model.ServiceRequest;

import java.util.Optional;

public interface PlacementStrategy {

    Optional<Integer> placeService(ServiceRequest request, FogNodeState localFcmState);
}
