package com.rtdwh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class QueryExecuteDTO {

    @NotBlank(message = "SQL语句不能为空")
    private String sql;

    @Min(value = 1, message = "最大返回行数必须大于0")
    private Integer maxRows = 1000;

    @Min(value = 1, message = "查询超时时间必须大于0")
    @Max(value = 1800, message = "查询超时时间不能超过1800秒")
    private Integer timeoutSeconds = 60;

    @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$", message = "请求ID格式不正确")
    private String requestId;
}
