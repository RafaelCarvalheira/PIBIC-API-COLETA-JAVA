package com.pibic.vrp.model;

import java.util.List;
import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class VrpSolution {
    private String problemId;
    private double totalCost;
    private List<RouteDTO> routes;
    private String status;
    private String message;
}
