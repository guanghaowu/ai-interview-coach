package com.aicoach.controller;

import com.aicoach.common.Result;
import com.aicoach.dto.LoginDTO;
import com.aicoach.dto.LoginVO;
import com.aicoach.dto.RegisterDTO;
import com.aicoach.dto.UpdateUserDTO;
import com.aicoach.dto.UserInfoVO;
import com.aicoach.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户 Controller
 *
 * 接口清单：
 * - POST /api/user/register   注册（无需登录）
 * - POST /api/user/login      登录（无需登录）
 * - GET  /api/user/info       获取当前用户信息（需要 JWT）
 * - PUT  /api/user/info       修改当前用户信息（仅昵称 / 头像）
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success(userService.register(dto));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(userService.login(dto));
    }

    @GetMapping("/info")
    public Result<UserInfoVO> info() {
        return Result.success(userService.getCurrentUser());
    }

    /** 只开放昵称与头像：用户名是登录凭据、配额属于服务端，都不允许自助修改 */
    @PutMapping("/info")
    public Result<UserInfoVO> updateInfo(@Valid @RequestBody UpdateUserDTO dto) {
        return Result.success(userService.updateCurrentUser(dto));
    }
}
