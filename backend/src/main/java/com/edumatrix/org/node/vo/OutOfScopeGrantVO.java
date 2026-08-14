package com.edumatrix.org.node.vo;

/**
 * 跨管辖授权明细（03-02 §3.4 响应的 {@code outOfScopeGrants} 元素，契约 §2.5 规则 9）。
 *
 * <p>移动后被移动子树仍持有、但<b>授予它的人已不在其祖先链上</b>的授权。
 * 契约把这类授权定为<b>合法状态</b>：不自动撤销，否则每次转移都会静默中断学员正在学的课程；
 * 但<b>降级为只读</b>——仅保留使用能力（学习、备课、组卷），丧失再下发能力，
 * 否则会形成资产穿透（教师调岗一次就把原校区的课程资产带进新校区并可无限复制）。
 *
 * <p><b>{@code resourceName} 在模块 06 恒为 {@code null}</b>：资源名在
 * {@code crs_course} / {@code qb_question} / {@code vod_video} 里，
 * 那三张表是模块 08/09/10 的涉及表，<b>不在模块 06 的涉及表内</b>（工单只给了
 * {@code org_resource_grant} 的只读）。模块 11 接上级联回收时一并补齐 ——
 * 判定口径的另一半（owner 侧）同样待补，理由见 {@code NodeGrantScopeMapper} 的类注释。
 */
public class OutOfScopeGrantVO {

    /** 受管资源类型：1 课程 2 题目 3 视频（契约 §5 {@code resource_type}）。 */
    private Integer resourceType;

    private Long resourceId;

    /** 资源名称。<b>模块 06 恒为 null</b>，见类注释。 */
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
