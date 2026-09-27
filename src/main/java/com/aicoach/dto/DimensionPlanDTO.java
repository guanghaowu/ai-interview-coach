package com.aicoach.dto;

import lombok.Data;

import java.util.List;

/**
 * Planner 的输出：把一份 JD 拆成若干个考察维度。
 *
 * 为什么要先拆维度：直接让模型"根据 JD 出 5 道题"，它很容易几道题都挤在同一个
 * 技术点上（比如全是 Redis）。先让 Planner 把 JD 拆成维度，再让 Executor 按维度
 * 出题，题目的覆盖面就可控、可解释——面试时也能说清"这 5 道题覆盖了哪 5 个方向"。
 */
@Data
public class DimensionPlanDTO {

    /** 考察维度清单，建议 3-5 个 */
    private List<DimensionItemDTO> dimensions;
}
