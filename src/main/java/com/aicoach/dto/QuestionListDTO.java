package com.aicoach.dto;

import lombok.Data;

import java.util.List;

/**
 * AI 返回的题目列表包装（LangChain4j 结构化输出用）
 */
@Data
public class QuestionListDTO {

    private List<QuestionDTO> questions;
}