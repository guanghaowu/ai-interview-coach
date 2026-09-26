package com.aicoach.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交回答入参
 */
@Data
public class SubmitAnswerDTO {

    @NotNull(message = "题目 ID 不能为空")
    private Long questionId;

    @NotBlank(message = "回答内容不能为空")
    @Size(max = 5000, message = "回答内容不能超过 5000 字")
    private String content;
}