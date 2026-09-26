package com.aicoach.mapper;

import com.aicoach.entity.Question;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面试题 Mapper
 */
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}