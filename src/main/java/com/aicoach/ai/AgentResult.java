package com.aicoach.ai;

import com.aicoach.dto.CritiqueDTO;
import com.aicoach.dto.DimensionPlanDTO;
import com.aicoach.dto.QuestionDTO;

import java.util.List;

/**
 * AgentLoop 的执行结果。
 *
 * 除了题目本身，还带上「怎么得到这些题目的」——考察计划、审核结论、实际轮数、
 * 以及哪一步降级过。出题过程因此是可解释的：前端能按维度分组展示，
 * 日志里也能还原 Agent 当时为什么改题。
 *
 * @param questions       最终题目列表
 * @param plan            考察计划，null 表示 Planner 降级未产出
 * @param critique        最终一轮的审核结论，null 表示 Critic 降级未产出
 * @param rounds          Executor 实际执行轮数（1 = 首轮即通过）
 * @param plannerDegraded Planner 是否降级
 * @param criticDegraded  Critic 是否降级
 */
public record AgentResult(List<QuestionDTO> questions,
                          DimensionPlanDTO plan,
                          CritiqueDTO critique,
                          int rounds,
                          boolean plannerDegraded,
                          boolean criticDegraded) {

    /** 是否发生了修订（跑了第二轮） */
    public boolean revised() {
        return rounds > 1;
    }

    /** 是否全程降级（Planner 与 Critic 都没跑成，等价于原来的单次出题） */
    public boolean fullyDegraded() {
        return plannerDegraded && criticDegraded;
    }
}
