package com.pibic.vrp.model;

import java.util.List;
import lombok.Data;

@Data
public class VrpInput {
    private String problemId;
    private GlobalParameters globalParameters;
    private List<Fleet> fleets;
    private List<Customer> customers;
    private List<CostMatrixEntry> costMatrix;
}
