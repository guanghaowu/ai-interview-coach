package com.aicoach.mapper;

import com.aicoach.entity.Feedback;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评分反馈 Mapper
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
}