package com.aicoach.service;

import com.aicoach.dto.FeedbackDTO;
import com.aicoach.entity.Answer;
import com.aicoach.entity.Feedback;
import com.aicoach.mapper.AnswerMapper;
import com.aicoach.mapper.FeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评分结果落库服务
 *
 * 为什么单独抽一个类，而不是直接在 AnswerConsumer 上加 @Transactional？
 * 因为 AI 评分耗时 5-15 秒。如果整个消费流程都在一个事务里，一个请求就会
 * 占着数据库连接 15 秒，Druid 连接池（max-active=20）并发 20 就爆了。
 * 所以事务只包住「插 feedback + 回填 answer」这两条写操作，外部调用留在事务外。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackMapper feedbackMapper;
    private final AnswerMapper answerMapper;

    /**
     * 原子写入评分结果：insert feedback + 回填 answer 的 score / feedbackId / status。
     *
     * 两步要么都成功、要么都回滚。否则 MQ 重投时会出现「feedback 插了、answer 没更新」，
     * 下次重试又插一条，feedback 表就多出重复行（该表没有唯一键兜底）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Feedback saveGradingResult(Answer answer, FeedbackDTO fb) {
        Feedback feedback = new Feedback();
        feedback.setAnswerId(answer.getId());
        feedback.setPros(fb.getPros());
        feedback.setCons(fb.getCons());
        feedback.setSuggestions(fb.getSuggestions());
        feedbackMapper.insert(feedback);

        answer.setScore(fb.getScore());
        answer.setFeedbackId(feedback.getId());
        answer.setStatus(1);
        answerMapper.updateById(answer);

        log.info("评分结果落库完成: answerId={}, feedbackId={}, score={}",
                answer.getId(), feedback.getId(), fb.getScore());
        return feedback;
    }
}
