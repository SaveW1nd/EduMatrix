package com.edumatrix.system.user.vo;

/**
 * 重置密码的响应体（03-01 §2.5）—— <b>只在服务端随机生成口令的那一支返回</b>。
 *
 * <p>调用方传了 {@code newPassword} 时响应 {@code data} 为 {@code null}
 * （§2.5 的请求示例就是那一支，回传调用方自己设的值毫无意义）。
 * 两支的取舍逐条见 {@code UserPasswordResetReq} 的类注释。
 *
 * <p><b>{@code initialPassword} 明文只在本次响应里出现一次</b>：不落库、不可再查。
 * 与 {@code TokenService} 里 refreshToken 原文「只在本次响应出现一次、服务端此后只存哈希」
 * 是同一条纪律。
 */
public class PasswordResetVO {

    /** 服务端随机生成的初始口令明文（≥12 位，含大小写字母 + 数字 + 符号）。 */
    private String initialPassword;

    public PasswordResetVO() {
    }

    public PasswordResetVO(String initialPassword) {
        this.initialPassword = initialPassword;
    }

    public String getInitialPassword() {
        return initialPassword;
    }

    public void setInitialPassword(String initialPassword) {
        this.initialPassword = initialPassword;
    }
}
