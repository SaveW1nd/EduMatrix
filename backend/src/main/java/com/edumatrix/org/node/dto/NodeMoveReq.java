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
     * 是否一并回收移动后跨出原上级管辖范围的资源授权（契约 §2.5 规则 9）。
     *
     * <h2>⚠ F-114 定案三（需方 2026-08-22）：<b>必填、没有默认值</b>，不传直接拒</h2>
     * <p>原来它是选填、默认 {@code false}。默认 {@code false} 的问题不在于选错了一边，
     * 而在于<b>「留下跨管辖授权」这件事可以在没有人做过任何决定的情况下发生</b> ——
     * 事后翻库只能看到「有跨管辖授权」，看不出那是有意为之还是漏了。
     *
     * <ul>
     *   <li>{@code true}  —— <b>现在回收</b>：同事务内级联撤销（走与接口 39 同一条）；</li>
     *   <li>{@code false} —— <b>我知道会留下跨管辖，是有意的</b>：授权保留，
     *       并把「是谁、什么时候选的」写进 {@code sys_oper_log}，同时在响应里回传。</li>
     * </ul>
     *
     * <p><b>用 {@code Boolean} 而不是 {@code boolean}</b>：原始类型的 {@code false}
     * 与「没传」在反序列化之后长得一模一样，{@code @NotNull} 就永远不会触发 ——
     * 那样这条定案会静默地不生效，而且全绿。
     */
    @NotNull(message = "不能为空（必须显式表态：true 现在回收 / false 我知道会留下跨管辖）")
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
}
