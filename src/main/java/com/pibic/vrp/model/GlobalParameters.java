package com.pibic.vrp.model;

import lombok.Data;

@Data
public class GlobalParameters {
    private int vehicleCapacity;
    private double vehicleFixedCost;
    private int numberOfCustomers;
    private int numberOfDepots;
}
