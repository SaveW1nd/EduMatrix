package com.edumatrix.org.node.vo;

/**
 * 跨管辖授权明细（03-02 §3.4 响应的 {@code outOfScopeGrants} 元素，契约 §2.5 规则 9）。
 *
 * <p>移动后被移动子树仍持有、但<b>授予它的人已不在其祖先链上</b>的授权。
 * 契约把这类授权定为<b>合法状态</b>：不自动撤销，否则每次转移都会静默中断学员正在学的课程；
 * 但<b>降级为只读</b>——仅保留使用能力（学习、备课、组卷），丧失再下发能力，
 * 否则会形成资产穿透（教师调岗一次就把原校区的课程资产带进新校区并可无限复制）。
 *
 * <h2>本模块的判定口径只做了一半（前端与联调都按这一半理解）</h2>
 * <p>契约 §2.5 规则 9 的完整判据是「授权行的 {@code target_node_id} 当前祖先链
 * <b>不再包含</b>该资源 {@code owner_node_id} <b>或其有效授权链</b>」。
 * 模块 06 实现的是 03-02 §3.4 措辞里<b>可算的那一半</b>：
 * <b>授权人所在节点（{@code grant_by} → {@code sys_user.node_id}）在移动后
 * 既不是目标节点自身、也不在其祖先链上</b>，即「由原上级授予、现已跨出其管辖范围」。
 *
 * <p><b>owner 侧那一半留模块 11</b>：它需要读 {@code crs_course} / {@code qb_question} /
 * {@code vod_video} 的 {@code owner_node_id} 并对授权链做递归，而那三张表是
 * 模块 08/09/10 的涉及表，<b>不在模块 06 的涉及表内</b>（工单只给了
 * {@code org_resource_grant} 的<b>只读</b>）。因此本模块返回的清单可能<b>偏窄</b>：
 * 少数「授权人仍在祖先链上、但资源 owner 已不在」的行不会被列出。
 * 模块 11 补齐后<b>响应结构不变</b>，只会多出行。
 *
 * <p>实现细节与逐条理由见 {@code NodeGrantScopeMapper} 的类注释。
 */
public class OutOfScopeGrantVO {

    /** 受管资源类型：1 课程 2 题目 3 视频（契约 §5 {@code resource_type}）。 */
    private Integer resourceType;

    private Long resourceId;

    /**
     * 资源名称。<b>⚠ 模块 06 恒为 {@code null}，模块 11 接上后才有值。</b>
     *
     * <p><b>这不是「这条授权没有名字」，而是「这个字段还没实现」</b> ——
     * 两者对前端是完全不同的两件事：前者该显示空白，后者该退化为按
     * {@code resourceType} + {@code resourceId} 展示（或干脆不渲染这一列），
     * 而不是为一个「本来就该有值」的字段写一层容错。
     *
     * <p><b>为什么本模块给不出</b>：资源名在 {@code crs_course}（课程）/
     * {@code qb_question}（题目）/ {@code vod_video}（视频）里，
     * 按 {@code resourceType} 分别取。那三张表是模块 08/09/10 的涉及表，
     * <b>不在模块 06 的涉及表内</b> —— 工单只给了 {@code org_resource_grant} 的<b>只读</b>。
     * 在这里越过工单去读它们，就是替那三个模块提前定下读取口径。
     *
     * <p><b>模块 11（资源授权引擎）接上后本字段填实值，响应结构不变</b>，
     * 前端届时无需改动，只是这一列从 {@code null} 变成有值。
     */
    private String resourceName;

    private Long targetNodeId;

    private String targetNodeName;

    public Integer getResourceType() {
        return resourceType;
    }

    public void setResourceType(Integer resourceType) {
        this.resourceType = resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public Long getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(Long targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    public String getTargetNodeName() {
        return targetNodeName;
    }

    public void setTargetNodeName(String targetNodeName) {
        this.targetNodeName = targetNodeName;
    }
}
