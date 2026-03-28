package com.pibic.vrp.model;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class Customer {
    private String id;

    // Demanda por carrier: {"1": 7, "2": 15}
    private Map<String, Integer> deliveryDemandByCarrier;
    private Map<String, Integer> pickupDemandByCarrier;

    private List<String> allowedCarriers;

    public int getTotalDeliveryDemand() {
        if (deliveryDemandByCarrier == null) return 0;
        return deliveryDemandByCarrier.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getTotalPickupDemand() {
        if (pickupDemandByCarrier == null) return 0;
        return pickupDemandByCarrier.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getDeliveryDemandForCarrier(String carrierId) {
        if (deliveryDemandByCarrier == null) return 0;
        return deliveryDemandByCarrier.getOrDefault(carrierId, 0);
    }

    public int getPickupDemandForCarrier(String carrierId) {
        if (pickupDemandByCarrier == null) return 0;
        return pickupDemandByCarrier.getOrDefault(carrierId, 0);
    }
}
