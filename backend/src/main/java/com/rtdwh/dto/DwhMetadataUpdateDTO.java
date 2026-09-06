package com.rtdwh.dto;

import lombok.Data;

import java.util.List;

@Data
public class DwhMetadataUpdateDTO {
    private String businessDesc;
    private String owner;
    private String businessDomain;
    private List<String> tags;
    private String sensitivityLevel;
    private String lifecycleStatus;
}
