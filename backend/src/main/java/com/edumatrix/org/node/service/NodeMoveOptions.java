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
     * 是否一并回收跨管辖授权（契约 §2.5 规则 9）。回收动作在模块 11 的
     * {@code OutOfScopeGrantResolver} 里，本模块负责在 {@code ancestors} 重算之后、
     * 同一事务内把它叫起来。
     */
    private boolean revokeOutOfScopeGrants;

    /**
     * 「不回收」这件事是不是<b>操作人显式选的</b>。
     *
     * <h2>为什么要多这一位，不能只看 {@code revokeOutOfScopeGrants == false}</h2>
     * <p>F-114 定案三要求：选了 {@code false} 就把「是谁、什么时候选的」写进
     * {@code sys_oper_log}。而 {@code false} 有<b>两个来源</b>：
     * <ul>
     *   <li>接口 4 的调用方<b>显式传了 {@code false}</b>（那是一次决定，要留痕）；</li>
     *   <li>模块 07 的分配导师 / 转交管理员 / 教师调岗<b>内部调用</b>，
     *       它们的语义里根本没有这个选项（{@link #none()}）。</li>
     * </ul>
     * <p>两者混在一起的话，写出来的那条留痕会说「某人选择了保留」——
     * <b>而那个人从来没被问过</b>。一条假的审计记录比没有更糟，
     * 与 {@code MemberOperLogWriter} 对 Job 路径「{@code user_id} 留 null 而不是填 0」
     * 是同一条纪律。
     */
    private boolean retentionChosenExplicitly;

    public NodeMoveOptions() {
    }

    public NodeMoveOptions(String reason, boolean revokeOutOfScopeGrants) {
        this.reason = reason;
        this.revokeOutOfScopeGrants = revokeOutOfScopeGrants;
    }

    /**
     * 接口 4 专用：操作人<b>显式表了态</b>（F-114 定案三，参数必填）。
     *
     * @param revoke 不可为 {@code null} —— 校验在 {@code NodeMoveReq} 上，走到这里必然有值
     */
    public static NodeMoveOptions explicitChoice(String reason, Boolean revoke) {
        NodeMoveOptions opts = new NodeMoveOptions(reason, Boolean.TRUE.equals(revoke));
        opts.retentionChosenExplicitly = true;
        return opts;
    }

    /**
     * 无原因、不回收的默认项 —— <b>内部封装调用专用</b>（分配导师 / 转交管理员 / 教师调岗）。
     *
     * <p><b>它不是「选了 false」</b>：这些接口的语义里没有这个选项，
     * 因此不写「有人选择了保留」的那条留痕（理由见 {@link #retentionChosenExplicitly}）。
     */
    public static NodeMoveOptions none() {
        return new NodeMoveOptions();
    }

    /** @see #retentionChosenExplicitly */
    public boolean isRetentionChosenExplicitly() {
        return retentionChosenExplicitly;
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
