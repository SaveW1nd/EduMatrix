package com.edumatrix.org.node.dto;

import jakarta.validation.constraints.Size;

/**
 * 重置人员密码请求（03-02 §3.6）。
 *
 * <h2>与 03-01 §2.5 的同名接口是两条路径，不是重复</h2>
 * <p>{@code /system/users/{id}/password/reset} 已收敛为<b>平台超管专用</b>（03-01 §2 导语）。
 * 机构管理员若没有本接口，就<b>无法给忘记密码的学员重置</b> ——
 * §3.6 的边注逐字：「收敛入口不等于砍掉能力」。
 *
 * <h2>响应形状与 03-01 §2.5 不同，这不是不一致</h2>
 * <p>§3.6 的响应字段说明写死了 {@code newPassword}「<b>仅本次响应返回一次</b>，
 * 供管理员转告本人」——<b>无论明文是调用方指定的还是服务端生成的</b>，
 * 都在响应里给（§3.6 响应示例即如此）。
 * 而 03-01 §2.5 在「调用方自己指定了密码」时返回 {@code null}。两处分册各写各的，按各自的来。
 */
public class NodePasswordResetReq {

    /**
     * 新密码，8~20 位且<b>同时含字母与数字</b>；<b>留空则服务端随机生成 ≥12 位强口令</b>。
     *
     * <p>「同时含字母与数字」是跨字符条件，判定放在 Service（正则表达可读性差）。
     * 不合规返回 <b>400</b>，不是业务码（§3.6：「密码格式不合规返回 400」）。
     *
     * <p><b>严禁以手机号后 6 位等可由账号推导的值作兜底</b>（§3.6 原文）：
     * {@code username} 即手机号，同源意味着<b>拿到名单即可登录任意账号</b>。
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
