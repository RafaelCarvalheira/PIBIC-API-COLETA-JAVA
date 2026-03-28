package com.pibic.vrp.model;

import java.util.List;
import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class RouteDTO {
    private String vehicleId;
    private List<String> activitySequence;
    private double routeCost;
}
