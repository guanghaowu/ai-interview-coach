package com.aicoach.dto;

import lombok.Data;

/**
 * 单个考察维度（Planner 拆解出的最小单元）
 */
@Data
public class DimensionItemDTO {

    /** 维度名，如「并发与线程安全」「MySQL 索引优化」 */
    private String name;

    /** 该维度出几道题 */
    private Integer count;

    /** 这个维度具体想考察什么（会作为出题提示传给 Executor） */
    private String focus;
}
