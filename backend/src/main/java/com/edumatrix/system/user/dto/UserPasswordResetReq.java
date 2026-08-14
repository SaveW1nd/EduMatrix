package com.edumatrix.system.user.dto;

import jakarta.validation.constraints.Size;

/**
 * 重置用户密码请求（03-01 §2.5）。<b>仅 {@code super_admin} 可调</b>。
 *
 * <h2>两个分支，响应形状不同 —— 这不是文档不一致</h2>
 * <ul>
 *   <li><b>传了 {@code newPassword}</b>（§2.5 的请求示例就是这一支）：用调用方指定的值，
 *       <b>响应 {@code data} 为 {@code null}</b> —— 调用方自己知道设的是什么，回传毫无意义；
 *   <li><b>不传</b>：服务端随机生成 ≥12 位强口令（含大小写字母 + 数字 + 符号），
 *       明文<b>仅在本次响应的 {@code initialPassword} 中返回一次</b>、不落库、不可再查。
 * </ul>
 * <p>§2.5 的正文写的是第二支、请求示例给的是第一支，两者说的是不同分支，<b>文档自洽</b>。
 * 记在这里是为了让下一个人不要把它当成不一致去"修"文档。
 *
 * <h2>禁止任何固定默认密码</h2>
 * <p>§2.5 参数表原文：固定常量会出现在文档与工单中，攻击者拿到用户名列表即可
 * <b>批量撞库命中所有「已重置未改密」的账号</b>。PRD F1-3 规则 3 同样禁止
 * 「手机号后 6 位」这类可由账号推导的兜底值。
 */
public class UserPasswordResetReq {

    /**
     * 新密码，8~20 位且含字母数字。<b>不传则由服务端随机生成</b>。
     *
     * <p>「同时含字母与数字」的判定在 Service（跨字符条件，正则可读性差），
     * 与 §2.2 共用同一处。不合规返回 <b>400</b>，不是业务码（§2.5「相关业务错误码：无」）。
     */
    @Size(min = 8, max = 20, message = "长度须为 8~20 位")
    private String newPassword;

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
