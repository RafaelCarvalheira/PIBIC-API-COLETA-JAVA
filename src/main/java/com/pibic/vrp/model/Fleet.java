package com.pibic.vrp.model;

import java.util.List;
import lombok.Data;

@Data
public class Fleet {
    private String carrierId;
    private String depotLocationId;
    private List<String> vehicleIds;
}
