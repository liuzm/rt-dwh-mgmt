package com.rtdwh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

public final class WorkflowDTO {
    private WorkflowDTO() {}

    @Data
    public static class DependencyRequest {
        @NotNull private Long upstreamTaskId;
        @NotNull private Long downstreamTaskId;
        private String conditionType = "data_available";
        private Long outputDatasetId;
    }

    @Data
    public static class PublishRequest {
        @NotBlank private String changeSummary;
    }

    @Data
    public static class BackfillRequest {
        @NotNull private LocalDate startDate;
        @NotNull private LocalDate endDate;
        private String parametersJson;
        private String bindingPolicy = "batch_only";
        private java.util.Map<Long, String> taskParametersJson;
    }

    @Data
    public static class CompleteRequest {
        @NotNull private Boolean success;
        private Long attemptId;
        private String executorId;
        private String errorMessage;
    }

    @Data
    public static class AttachJobRequest {
        @NotBlank private String executorId;
        @NotBlank private String externalJobId;
        private Long attemptId;
    }

    @Data
    public static class ParameterContractRequest { private String parameterSchemaJson; }

    @Data
    public static class ScheduleRequest {
        @NotBlank private String cronExpression;
        @NotBlank private String timezone = "Asia/Shanghai";
        private Integer businessDateOffset = -1;
        private String parametersJson;
        private Boolean enabled = true;
    }

    @Data
    public static class OutputDatasetRequest {
        @NotBlank private String catalogName;
        @NotBlank private String databaseName;
        @NotBlank private String tableName;
        @NotBlank private String layer;
        private String owner;
        private String businessDesc;
        private Integer slaMinutes = 1440;
        private Boolean qualityGateEnabled = false;
    }
}
