package com.edumatrix.org.node.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 移动节点请求（03-02 §3.4）。
 *
 * <p><b>本接口对客户端不可自动重试</b>（00-通用约定 §7.5）：超时后节点可能已移动成功，
 * 盲目重试会把它再移到另一位置。客户端应改为重新拉取节点详情确认当前 {@code parentId}。
 * <b>因此也不给它加 {@code @Idempotent}</b> —— 幂等键会把「重试是安全的」这个错误印象
 * 交给调用方，而这个接口恰恰不安全。
 */
public class NodeMoveReq {

    /** 目标父节点 ID。 */
    @NotNull(message = "不能为空")
    private Long toParentId;

    /**
     * 是否一并回收移动后跨出原上级管辖范围的资源授权，默认 {@code false}（契约 §2.5 规则 9）。
     *
     * <p>{@code false} 时保留这些授权并在响应的 {@code outOfScopeGrants} 中列出，
     * 由操作者决定后续处理。
     *
     * <p><b>模块 06 只做字段与开关，回收动作在模块 11 接上</b>
     * （04-实施计划.md 模块 06 规则 8）—— 处置见 {@code NodeMoveService} 里的注释。
     */
    private Boolean revokeOutOfScopeGrants;

    /** 异动原因，写入 {@code org_node_change_log.reason}，最长 500 字符。 */
    @Size(max = 500, message = "最长 500 字符")
    private String reason;

    public Long getToParentId() {
        return toParentId;
    }

    public void setToParentId(Long toParentId) {
        this.toParentId = toParentId;
    }

    public Boolean getRevokeOutOfScopeGrants() {
        return revokeOutOfScopeGrants;
    }

    public void setRevokeOutOfScopeGrants(Boolean revokeOutOfScopeGrants) {
        this.revokeOutOfScopeGrants = revokeOutOfScopeGrants;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isRevokeOutOfScopeGrants() {
        return Boolean.TRUE.equals(revokeOutOfScopeGrants);
    }
}
