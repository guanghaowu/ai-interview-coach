package com.aicoach.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.aicoach.common.BusinessException;
import com.aicoach.common.JwtUtil;
import com.aicoach.common.ThreadLocalUtil;
import com.aicoach.dto.LoginDTO;
import com.aicoach.dto.LoginVO;
import com.aicoach.dto.RegisterDTO;
import com.aicoach.dto.UserInfoVO;
import com.aicoach.entity.User;
import com.aicoach.mapper.UserMapper;
import com.aicoach.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现
 *
 * 注意：BCryptPasswordEncoder 不是单例，但创建开销小可以 new；
 * 也可以声明成 @Bean 全局共享。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Override
    public LoginVO register(RegisterDTO dto) {
        // 1. 检查用户名是否已注册
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername())
        );
        if (count != null && count > 0) {
            throw new BusinessException("该用户名已被注册");
        }

        // 2. 构造用户
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(PASSWORD_ENCODER.encode(dto.getPassword()));
        user.setNickname(dto.getUsername()); // 默认昵称 = 用户名
        user.setDailyQuota(10);

        // 3. 插入
        userMapper.insert(user);
        log.info("用户注册成功: id={}, username={}", user.getId(), user.getUsername());

        // 4. 返回 token + 用户信息
        return buildLoginVO(user);
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        // 1. 查用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername())
        );
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        // 2. 校验密码（注意：参数顺序是 rawPassword, encodedPassword）
        if (!PASSWORD_ENCODER.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        log.info("用户登录成功: id={}, username={}", user.getId(), user.getUsername());

        // 3. 返回
        return buildLoginVO(user);
    }

    @Override
    public UserInfoVO getCurrentUser() {
        Long userId = ThreadLocalUtil.get();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return BeanUtil.copyProperties(user, UserInfoVO.class);
    }

    /**
     * 构造登录返回：生成 token + 用户基本信息
     */
    private LoginVO buildLoginVO(User user) {
        String token = jwtUtil.generate(user.getId(), user.getUsername());
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setDailyQuota(user.getDailyQuota());
        return vo;
    }
}