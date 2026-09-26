package com.aicoach.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息返回（不包含密码）
 */
@Data
public class UserInfoVO {

    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    private Integer dailyQuota;

    private LocalDateTime createdAt;
}