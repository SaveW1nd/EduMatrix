package com.edumatrix.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求（03-01 §1.2 的四个参数，逐字对齐）。
 *
 * <p>四类角色共用本接口，<b>服务端按账号自动识别 {@code userType}</b> ——
 * 请求体里没有「我是老师还是学生」这样的字段，也不该有：那等于让客户端声明自己的角色。
 */
public class LoginRequest {

    /** 用户名，4~30 位（03-01 §1.2）。 */
    @NotBlank(message = "username 不能为空")
    @Size(min = 4, max = 30, message = "username 长度须为 4~30 位")
    private String username;

    /**
     * 明文密码，8~20 位（HTTPS 传输，服务端 BCrypt 比对）。
     *
     * <p><b>长度校验为什么留在这里</b>：它是 03-01 §1.2 写死的参数约束，属于参数校验（400），
     * 与 PRD §7.3 的弱密码策略（改密时强制、{@link com.edumatrix.auth.service.PasswordService}
     * 负责）是两件事 —— 登录不该因为「历史密码不含数字」而把人挡在门外。
     */
    @NotBlank(message = "password 不能为空")
    @Size(min = 8, max = 20, message = "password 长度须为 8~20 位")
    private String password;

    /** 验证码标识，来自 §1.1，原样带回。 */
    @NotBlank(message = "captchaKey 不能为空")
    private String captchaKey;

    /** 用户输入的验证码，4 位，不区分大小写。 */
    @NotBlank(message = "captchaCode 不能为空")
    @Size(min = 4, max = 4, message = "captchaCode 须为 4 位")
    private String captchaCode;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getCaptchaKey() {
        return captchaKey;
    }

    public void setCaptchaKey(String captchaKey) {
        this.captchaKey = captchaKey;
    }

    public String getCaptchaCode() {
        return captchaCode;
    }

    public void setCaptchaCode(String captchaCode) {
        this.captchaCode = captchaCode;
    }
}
