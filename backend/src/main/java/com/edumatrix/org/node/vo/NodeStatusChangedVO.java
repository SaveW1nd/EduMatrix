package com.edumatrix.org.node.vo;

import java.time.LocalDateTime;

/**
 * 节点停用 / 启用的响应（03-02 §3.5）。
 *
 * <p><b>库里只写了 1 行</b>（{@code org_node.status}）。这里的两个计数是<b>影响面</b>，
 * 不是写入行数 —— §3.5 原文：「实现走登录时查祖先链，<b>不做级联写库</b>；停用只改本节点 1 行」。
 * 级联写库方案要为一个 1.1 万人的分支写 2.2 万行，中途失败留下半停用状态，
 * 且恢复时无法区分「被级联停的」与「本来就单独停的」。
 */
public class NodeStatusChangedVO {

    private Long nodeId;

    /** 0 正常 1 停用。 */
    private Integer status;

    /**
     * 受影响的节点数。
     *
     * <p>按 §3.5「停用效果按节点类型自动区分」：
     * <ul>
     *   <li><b>管理员节点</b>（{@code node_type=1}）→ 本人 + 整棵子树（分支冻结）；
     *   <li><b>教师 / 学生节点</b> → 恒为 <b>1</b>，仅本人。教师停用时名下学员
     *       <b>照常登录学习</b>，把他们算进来就是把一件没发生的事写进响应
     *       —— 契约 §2.3 称级联停学员为「业务事故」。
     * </ul>
     */
    private int affectedNodeCount;

    /**
     * 受影响的账号数。<b>恒等于 {@link #affectedNodeCount}</b> ——
     * 契约 §2.3「每个节点都是一个人」，{@code ref_user_id} 全部非空且一人一节点
     * （{@code uk_ref_user_id}）。两个字段并列只为调用方按语义取用。
     */
    private int affectedUserCount;

    private LocalDateTime updateTime;

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public int getAffectedNodeCount() {
        return affectedNodeCount;
    }

    public void setAffectedNodeCount(int affectedNodeCount) {
        this.affectedNodeCount = affectedNodeCount;
    }

    public int getAffectedUserCount() {
        return affectedUserCount;
    }

    public void setAffectedUserCount(int affectedUserCount) {
        this.affectedUserCount = affectedUserCount;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
