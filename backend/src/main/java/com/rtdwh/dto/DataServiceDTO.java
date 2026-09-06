package com.rtdwh.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDateTime;

public final class DataServiceDTO {
    private DataServiceDTO() {}
    @Data public static class DefinitionRequest {
        @Min(0) private Long expectedRevision;
        @NotBlank @Pattern(regexp="^[a-z][a-z0-9_-]{2,63}$") private String serviceCode;
        @NotBlank @Size(max=128) private String serviceName;
        @Size(max=512) private String description;
        @NotBlank private String sqlTemplate;
        private String parameterConfig;
        @NotBlank private String catalogName = "rtdwh_paimon";
        @NotBlank private String databaseName = "ods";
        @NotNull @Min(1) @Max(50000) private Integer maxRows = 1000;
        @NotNull @Min(1) @Max(1800) private Integer timeoutSeconds = 30;
        @NotNull @Min(1) @Max(100000) private Integer rateLimitPerMinute = 60;
    }
    @Data public static class PublicationRequest {
        @NotNull @Min(0) private Long expectedRevision;
        @Size(max=512) private String changeSummary;
    }
    public record PublicationPreview(Long revision, Long currentVersionId, boolean publishable,
                                     java.util.List<String> changes, java.util.List<String> breakingChanges,
                                     java.util.List<com.rtdwh.service.DataServiceContractService.Column> resultColumns,
                                     java.util.List<com.rtdwh.service.ViewSqlService.Name> dependencies,
                                     String compatibilityBasis) {}
    @Data public static class AppRequest {
        @NotBlank @Size(max=128) private String appName;
        @Future private LocalDateTime expiresAt;
    }
    @Data public static class GrantRequest { @NotNull private Long serviceId; }
    public record AppCredential(Long id, String appName, String appKey, String appSecret,
                                Boolean enabled, LocalDateTime expiresAt) {}
}
