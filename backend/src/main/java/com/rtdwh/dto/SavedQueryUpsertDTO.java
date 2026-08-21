package com.rtdwh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SavedQueryUpsertDTO {

    @NotBlank(message = "SQL 名称不能为空")
    @Size(max = 128, message = "SQL 名称不能超过 128 个字符")
    private String name;

    @NotBlank(message = "SQL 内容不能为空")
    private String sqlText;

    @Size(max = 512, message = "描述不能超过 512 个字符")
    private String description;

    @Size(max = 256, message = "标签不能超过 256 个字符")
    private String tags;
}
