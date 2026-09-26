package com.aicoach.mapper;

import com.aicoach.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 * 继承 BaseMapper 自动拥有：insert / updateById / selectById / deleteById / selectList 等
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}