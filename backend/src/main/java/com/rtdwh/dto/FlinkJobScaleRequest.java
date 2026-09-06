package com.rtdwh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FlinkJobScaleRequest {

    @Min(value = 1, message = "目标并行度不能小于 1")
    @Max(value = 32768, message = "目标并行度不能大于 32768")
    private int targetParallelism;

    @NotBlank(message = "expectedJobId 不能为空")
    private String expectedJobId;

    @NotNull(message = "expectedConfiguredParallelism 不能为空")
    @Min(value = 1, message = "期望当前并行度不能小于 1")
    private Integer expectedConfiguredParallelism;

    @NotBlank(message = "调整原因不能为空")
    @Size(max = 256, message = "调整原因不能超过 256 个字符")
    private String reason;
}
