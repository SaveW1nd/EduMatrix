package com.edumatrix.org.node.service;

/**
 * {@code NodeMoveService.move(nodeId, toParentId, opts)} 的可选项
 * （04-实施计划.md 模块 06「对外产出」逐字给出的签名）。
 *
 * <p>做成一个对象而不是两个参数：模块 07 的分配导师 / 转交管理员 / 教师调岗都要调它，
 * 将来若 §3.4 再加可选项（F-21 的预检披露就在候选里），加字段不会改所有调用点的签名。
 */
public class NodeMoveOptions {

    /** 异动原因，写入 {@code org_node_change_log.reason}，最长 500 字符。 */
    private String reason;

    /**
     * 是否一并回收跨管辖授权，默认 {@code false}（契约 §2.5 规则 9）。
     *
     * <p><b>本模块只做字段与开关，不执行回收</b> —— 04-实施计划.md 模块 06 规则 8 逐字：
     * 「本模块先把字段与开关做出来，<b>级联回收动作在模块 11 接上</b>」，
     * 且工单的「涉及表」把 {@code org_resource_grant} 列在<b>只读</b>栏。
     * 处置见 {@code NodeMoveService} 里的注释。
     */
    private boolean revokeOutOfScopeGrants;

    public NodeMoveOptions() {
    }

    public NodeMoveOptions(String reason, boolean revokeOutOfScopeGrants) {
        this.reason = reason;
        this.revokeOutOfScopeGrants = revokeOutOfScopeGrants;
    }

    /** 无原因、不回收的默认项。 */
    public static NodeMoveOptions none() {
        return new NodeMoveOptions();
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isRevokeOutOfScopeGrants() {
        return revokeOutOfScopeGrants;
    }

    public void setRevokeOutOfScopeGrants(boolean revokeOutOfScopeGrants) {
        this.revokeOutOfScopeGrants = revokeOutOfScopeGrants;
    }
}
