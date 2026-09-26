package com.aicoach.mapper;

import com.aicoach.entity.InterviewSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面试会话 Mapper
 */
@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSession> {
}