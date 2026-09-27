package com.aicoach.dto;

import lombok.Data;

import java.util.List;

/**
 * Critic 的输出：对一批题目做质量审核的结论。
 *
 * 注意 passed 用包装类型 Boolean 而不是 boolean：模型有可能漏返回这个字段，
 * 包装类型拿到 null 时调用方可以按「放行」处理（fail-open），
 * 而基本类型会直接被反序列化成 false，导致明明没问题的题目被反复重出。
 */
@Data
public class CritiqueDTO {

    /** 是否通过审核。null 视为通过 */
    private Boolean passed;

    /** 总体结论，一句话 */
    private String reason;

    /** 具体问题清单，如「第 2 题与第 4 题都在问 Redis 缓存穿透」 */
    private List<String> issues;

    /** JD 里提到但没被覆盖到的维度，用于第二轮定向补题 */
    private List<String> missingDimensions;
}
