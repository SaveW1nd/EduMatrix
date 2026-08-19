package com.edumatrix.system.log.vo;

import java.time.LocalDateTime;

/**
 * §8.2 操作日志的响应体（字段集逐条对齐 03-01 §8.2 的响应示例与字段说明）。
 *
 * <p><b>{@code username} / {@code realName} 可为 {@code null}</b>：
 * {@code sys_oper_log} 只有 {@code user_id}，而它在 Job / Worker / 事件消费路径上
 * 就是 {@code null}（{@code OperLogWriter} 的四档表 —— 填 0 会指向平台超管，
 * 那是一条假审计记录）。前端对这类行应显示「系统任务」一类的占位，不要显示空白。
 *
 * <p>{@code params} 是<b>已脱敏</b>的 JSON 文本（§8.2 字段说明逐字：
 * 「请求参数 JSON 字符串（密码等敏感字段已脱敏，不落库）」），
 * 脱敏发生在写入时（{@code SensitiveParamMasker}），<b>不是查询时</b> ——
 * 查询时脱敏等于承认库里躺着明文。
 *
 * <p>{@code status} / {@code errorMsg} 不在 §8.2 的响应示例里，但在 DDL 里
 * 且是 F-25 点名要求切面承担的一件事。<b>返回它们不算越出分册</b>：
 * §8.2 的字段说明表只解释了 {@code method} / {@code params} / {@code costMs} 三项，
 * 不是响应字段的穷举（示例里的 {@code id} / {@code userId} 等也未在表中）。
 * 不返回的话，「哪些操作失败了」在页面上完全看不见，而那正是审计要看的部分。
 */
public class OperLogVO {

    private Long id;
    private Long userId;
    /** 可为 null，见类注释。 */
    private String username;
    /** 可为 null，见类注释。 */
    private String realName;
    private String module;
    private String action;
    /** HTTP 方法 + 路径；无请求上下文时是 Java 方法签名（DDL 注释两种都在册）。 */
    private String method;
    /** 已脱敏的 JSON 文本；{@code saveParams = false} 时为 null。 */
    private String params;
    private String ip;
    /** 接口耗时（毫秒）。 */
    private Integer costMs;
    /** 0 成功 1 失败。业务码拒绝（如 10011）算失败。 */
    private Integer status;
    /** 失败信息，成功时为 null。 */
    private String errorMsg;
    private LocalDateTime operTime;

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

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getCostMs() {
        return costMs;
    }

    public void setCostMs(Integer costMs) {
        this.costMs = costMs;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getOperTime() {
        return operTime;
    }

    public void setOperTime(LocalDateTime operTime) {
        this.operTime = operTime;
    }
}
