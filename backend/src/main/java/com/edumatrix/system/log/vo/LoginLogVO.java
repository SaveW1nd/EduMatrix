package com.edumatrix.system.log.vo;

import java.time.LocalDateTime;

/**
 * §8.1 登录日志的响应体（字段集逐条对齐 03-01 §8.1 的响应示例与字段说明）。
 *
 * <p><b>{@code realName} 来自 {@code LEFT JOIN sys_user}，可以为 {@code null}</b>：
 * {@code sys_login_log} 没有这一列，而 {@code user_id} 在「登录失败且账号不存在」时
 * 就是 {@code null}（DDL 注释逐字）。前端要能显示空值 —— 那种行恰恰是撞库排查的主角。
 *
 * <p>{@code id} / {@code userId} 声明为 {@code Long}，由 {@code common/id} 的全局
 * Jackson 序列化器统一转成字符串（{@code 00-通用约定} §5），本类不逐字段加注解。
 */
public class LoginLogVO {

    private Long id;
    private Long userId;
    private String username;
    /** 可为 null，见类注释。 */
    private String realName;
    private String ip;
    private String userAgent;
    /** 0 成功 1 失败。 */
    private Integer status;
    /** 结果描述（失败原因或「登录成功」）。 */
    private String msg;
    private LocalDateTime loginTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public LocalDateTime getLoginTime() {
        return loginTime;
    }

    public void setLoginTime(LocalDateTime loginTime) {
        this.loginTime = loginTime;
    }
}
