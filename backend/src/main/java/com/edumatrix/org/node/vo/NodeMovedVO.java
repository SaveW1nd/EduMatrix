package com.edumatrix.org.node.vo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 移动节点的响应（03-02 §3.4）。
 *
 * <p>也是 {@code NodeMoveService.move(...)} 的返回值 —— 模块 07 的分配导师、
 * 转交管理员、教师调岗都拿它（04-实施计划.md 模块 06「对外产出」：
 * 「返回含 {@code changeType} / {@code affectedNodeCount} / {@code outOfScopeGrants}」）。
 */
public class NodeMovedVO {

    private Long nodeId;
    private String nodeName;
    private Integer nodeType;

    private Long fromParentId;
    private String fromParentName;
    private Long toParentId;
    private String toParentName;

    /** 被移动节点<b>重算后</b>的祖级路径。 */
    private String newAncestors;

    /** 本次异动类型，按 §3.4 映射表自动推断（2 分配导师 / 3 转交管理员 / 4 教师调岗 / 8 节点移动）。 */
    private Integer changeType;

    /**
     * 本次重算 {@code ancestors} 的节点总数，<b>含被移动节点自身</b>。
     *
     * <p>= 步骤 5 那条前缀替换 UPDATE 的返回行数（全部后代） <b>+ 1</b>，
     * 而那个 <b>+1 是步骤 4 单独更新的被移动节点自身</b>，<b>不是差一错误</b>
     * （见 {@code OrgNodeMapper#rebuildSubtreeAncestors} 的注释）。
     *
     * <p>它是「第 5 步那条 UPDATE 是否真的命中了整棵子树」的<b>唯一外部可见证据</b>：
     * 移动一棵含 12 个后代的子树，这里必须是 13。
     */
    private int affectedNodeCount;

    private int outOfScopeGrantCount;

    private List<OutOfScopeGrantVO> outOfScopeGrants = new ArrayList<>();

    /**
     * 本次<b>是否回收</b>了跨管辖授权（F-114 定案三，接口 4 的 {@code revokeOutOfScopeGrants} 原样回传）。
     *
     * <p><b>为什么要把入参回传出来</b>：{@code outOfScopeGrants} 这个清单在两种情况下
     * 长得一模一样 —— 「选了回收，且已经全撤了」与「选了保留，这些就是留下的」。
     * 光看清单分不出来，而<b>这两件事的后续动作完全相反</b>。
     *
     * <p>内部封装调用（分配导师 / 转交管理员 / 教师调岗）走 {@code NodeMoveOptions.none()}，
     * 这里回 {@code false}。
     */
    private boolean revokedOutOfScopeGrants;

    /**
     * 「保留跨管辖授权」这个决定<b>是不是操作人显式做的</b>，以及记在了哪里。
     *
     * <p>{@code true} 时 {@code sys_oper_log} 里有一行
     * {@code action = 保留跨管辖授权}，含操作人与时间戳（F-114 定案三：
     * 「{@code false} 时把是谁、什么时候选的写进 {@code sys_oper_log} 与响应」）。
     */
    private boolean retentionRecorded;

    private LocalDateTime changeTime;

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Integer getNodeType() {
        return nodeType;
    }

    public void setNodeType(Integer nodeType) {
        this.nodeType = nodeType;
    }

    public Long getFromParentId() {
        return fromParentId;
    }

    public void setFromParentId(Long fromParentId) {
        this.fromParentId = fromParentId;
    }

    public String getFromParentName() {
        return fromParentName;
    }

    public void setFromParentName(String fromParentName) {
        this.fromParentName = fromParentName;
    }

    public Long getToParentId() {
        return toParentId;
    }

    public void setToParentId(Long toParentId) {
        this.toParentId = toParentId;
    }

    public String getToParentName() {
        return toParentName;
    }

    public void setToParentName(String toParentName) {
        this.toParentName = toParentName;
    }

    public String getNewAncestors() {
        return newAncestors;
    }

    public void setNewAncestors(String newAncestors) {
        this.newAncestors = newAncestors;
    }

    public Integer getChangeType() {
        return changeType;
    }

    public void setChangeType(Integer changeType) {
        this.changeType = changeType;
    }

    public int getAffectedNodeCount() {
        return affectedNodeCount;
    }

    public void setAffectedNodeCount(int affectedNodeCount) {
        this.affectedNodeCount = affectedNodeCount;
    }

    public int getOutOfScopeGrantCount() {
        return outOfScopeGrantCount;
    }

    public void setOutOfScopeGrantCount(int outOfScopeGrantCount) {
        this.outOfScopeGrantCount = outOfScopeGrantCount;
    }

    public List<OutOfScopeGrantVO> getOutOfScopeGrants() {
        return outOfScopeGrants;
    }

    public void setOutOfScopeGrants(List<OutOfScopeGrantVO> outOfScopeGrants) {
        this.outOfScopeGrants = outOfScopeGrants == null ? new ArrayList<>() : outOfScopeGrants;
    }

    public LocalDateTime getChangeTime() {
        return changeTime;
    }

    public void setChangeTime(LocalDateTime changeTime) {
        this.changeTime = changeTime;
    }

    public boolean isRevokedOutOfScopeGrants() {
        return revokedOutOfScopeGrants;
    }

    public void setRevokedOutOfScopeGrants(boolean revokedOutOfScopeGrants) {
        this.revokedOutOfScopeGrants = revokedOutOfScopeGrants;
    }

    public boolean isRetentionRecorded() {
        return retentionRecorded;
    }

    public void setRetentionRecorded(boolean retentionRecorded) {
        this.retentionRecorded = retentionRecorded;
    }
}
