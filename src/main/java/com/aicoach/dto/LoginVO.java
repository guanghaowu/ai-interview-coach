package com.aicoach.dto;

import lombok.Data;

/**
 * 登录返回：token + 用户信息
 */
@Data
public class LoginVO {

    private String token;

    private Long userId;

    private String username;

    private String nickname;

    private Integer dailyQuota;
}