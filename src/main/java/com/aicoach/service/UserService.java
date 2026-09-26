package com.aicoach.service;

import com.aicoach.dto.LoginDTO;
import com.aicoach.dto.LoginVO;
import com.aicoach.dto.RegisterDTO;
import com.aicoach.dto.UserInfoVO;

/**
 * 用户服务
 */
public interface UserService {

    /** 注册 */
    LoginVO register(RegisterDTO dto);

    /** 登录 */
    LoginVO login(LoginDTO dto);

    /** 获取当前登录用户信息 */
    UserInfoVO getCurrentUser();
}