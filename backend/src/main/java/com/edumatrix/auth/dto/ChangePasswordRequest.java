package com.edumatrix.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 本人修改密码（03-01 §1.6）。
 *
 * <p>{@code newPassword} 的<b>格式与强度校验不写成注解</b>：§1.6 要求
 * 「8~20 位且同时含字母与数字，且不得与原密码相同」，最后一条是<b>跨字段</b>的，
 * 注解表达不了。三条规则拆在两处会让「哪条先判、返回什么」变得含糊，
 * 故一并交给 {@link com.edumatrix.auth.service.PasswordService#assertStrongEnough}，
 * 统一返回 400。
 */
public class ChangePasswordRequest {

    /** 原密码（明文，HTTPS 传输，服务端 BCrypt 比对）；不匹配返回 {@code 10014}。 */
    @NotBlank(message = "oldPassword 不能为空")
    private String oldPassword;

    /** 新密码，8~20 位且同时含字母与数字，不得与原密码相同（不合规返回 400）。 */
    @NotBlank(message = "newPassword 不能为空")
    private String newPassword;

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
