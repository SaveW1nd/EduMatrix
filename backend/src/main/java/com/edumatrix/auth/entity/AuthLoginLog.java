package com.edumatrix.auth.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code sys_login_log} 实体 —— <b>不继承 {@code BaseEntity} / {@code TenantEntity}</b>。
 *
 * <p>05-工程结构.md §E 明列：{@code sys_login_log} / {@code sys_oper_log} /
 * {@code vod_play_auth_log} / {@code vod_heartbeat_log} <b>四张日志表不继承基类，
 * 字段自行声明</b>（契约 §2.2 末尾的日志例外）。原因是这四张表没有基类假定的全套通用字段
 * （本表就没有 {@code create_by} / {@code create_time} / {@code remark}），
 * 硬套基类会让 MyBatis-Plus 往 SQL 里塞不存在的列，运行期 {@code Unknown column}。
 *
 * <p><b>{@code tenant_id} 不在这里赋值</b>：本表有该列，租户插件会在 INSERT 时注入。
 * 写日志的调用方用 {@code TenantHelper.runWithTenant(...)} 提供租户上下文 ——
 * 登录失败且账号不存在时那一刻还不知道租户，按 DDL 的默认值口径写 {@code 0}。
 */
@TableName("sys_login_log")
public class AuthLoginLog {

    /** 登录结果：成功。 */
    public static final int STATUS_SUCCESS = 0;
    /** 登录结果：失败（PRD F1-1 验收标准要求失败也留痕）。 */
    public static final int STATUS_FAIL = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录失败且账号不存在时为 {@code null}（DDL 注释逐字如此）。 */
    private Long userId;

    private String username;

    private String ip;

    private String userAgent;

    /** 0 成功 1 失败。 */
    private Integer status;

    /** 结果描述（如「密码错误」「机构服务已到期」），{@code VARCHAR(255)}。 */
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
