package com.aicoach.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建面试会话入参
 */
@Data
public class CreateSessionDTO {

    @NotBlank(message = "JD 内容不能为空")
    @Size(min = 20, max = 5000, message = "JD 长度需在 20-5000 之间")
    private String jdContent;
}