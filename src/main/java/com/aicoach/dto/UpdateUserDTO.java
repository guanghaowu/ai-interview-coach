package com.aicoach.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改当前用户信息入参
 *
 * 只开放昵称与头像：username 是登录凭据、password 走独立流程、
 * dailyQuota 属于服务端配额，都不允许用户自助修改。
 */
@Data
public class UpdateUserDTO {

    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    @Size(max = 255, message = "头像地址不能超过 255 个字符")
    private String avatar;
}
