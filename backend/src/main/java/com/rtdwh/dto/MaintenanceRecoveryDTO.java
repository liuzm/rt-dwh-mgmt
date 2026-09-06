package com.rtdwh.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class MaintenanceRecoveryDTO {
    @NotNull @Min(0) private Long expectedRevision;
    @NotBlank @Pattern(regexp = "observe|retry_cleanup|attach_job|cancel_preparation|cancel_pending|note") private String action;
    @NotBlank @Size(max = 1000) private String reason;
    @Pattern(regexp = "[a-fA-F0-9]{32}") private String jobId;
}
