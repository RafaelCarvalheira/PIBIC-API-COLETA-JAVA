package com.pibic.vrp.model;

import lombok.Data;

@Data
public class CostMatrixEntry {
    private String from;
    private String to;
    private double cost;
}
