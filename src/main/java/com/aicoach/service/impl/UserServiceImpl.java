package com.aicoach.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.aicoach.common.BusinessException;
import com.aicoach.common.JwtUtil;
import com.aicoach.common.ThreadLocalUtil;
import com.aicoach.dto.LoginDTO;
import com.aicoach.dto.LoginVO;
import com.aicoach.dto.RegisterDTO;
import com.aicoach.dto.UpdateUserDTO;
import com.aicoach.dto.UserInfoVO;
import com.aicoach.entity.User;
import com.aicoach.mapper.UserMapper;
import com.aicoach.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
        return BeanUtil.copyProperties(requireCurrentUser(), UserInfoVO.class);
    }

    @Override
    public UserInfoVO updateCurrentUser(UpdateUserDTO dto) {
        Long userId = requireLogin();

        // 用 UpdateWrapper 做定向更新，只 set 允许改的字段。
        // 若直接 updateById(user)，password 等字段会一并写回，
        // 一旦实体与库中数据不一致就有被覆盖的风险。
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<User>().eq(User::getId, userId);
        boolean changed = false;
        if (StrUtil.isNotBlank(dto.getNickname())) {
            update.set(User::getNickname, dto.getNickname());
            changed = true;
        }
        if (dto.getAvatar() != null) {
            // 传空串表示清空头像
            update.set(User::getAvatar, dto.getAvatar());
            changed = true;
        }
        if (!changed) {
            throw new BusinessException(400, "没有需要更新的字段");
        }
        userMapper.update(null, update);

        User user = requireCurrentUser();
        log.info("用户信息已更新: id={}, nickname={}", userId, user.getNickname());
        return BeanUtil.copyProperties(user, UserInfoVO.class);
    }

    /** 取当前登录用户 id，未登录直接 401 */
    private Long requireLogin() {
        Long userId = ThreadLocalUtil.get();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        return userId;
    }

    /** 取当前登录用户实体，不存在则报错 */
    private User requireCurrentUser() {
        Long userId = requireLogin();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
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
