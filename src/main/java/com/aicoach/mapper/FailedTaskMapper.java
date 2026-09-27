package com.aicoach.mapper;

import com.aicoach.entity.FailedTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 失败任务 Mapper
 */
@Mapper
public interface FailedTaskMapper extends BaseMapper<FailedTask> {
}
