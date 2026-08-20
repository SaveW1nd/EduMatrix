package com.edumatrix.org.grant.vo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 接口 39 撤销资源授权（级联子树）的响应（03-02 §9.3 响应示例逐字段）。
 *
 * <p>{@code learningRecordsRetained} <b>恒为 {@code true}</b>：撤销是一个
 * <b>权限动作，不是数据销毁动作</b>。{@code vod_watch_progress} / {@code hw_answer_sheet} /
 * {@code hw_wrong_book} / {@code stat_*} 全部保留，学员只是失去继续访问权。
 */
public class GrantRevokedVO {

    private Integer resourceType;
    private Integer resourceCount;
    private Integer targetNodeCount;

    /** 本次撤销的总行数（直接 + 级联）。 */
    private Integer revokedCount;

    /** 直接撤销的行数（{@code target_node_id} 恰为请求中的节点）。 */
    private Integer directRevokedCount;

    /** <b>级联</b>撤销的行数（目标节点子树内的下级持有行）。 */
    private Integer cascadeRevokedCount;

    /** 受影响的节点数（去重）。 */
    private Integer affectedNodeCount;

    /**
     * 受影响的<b>学员</b>数（{@code node_type = 3}，去重）。
     *
     * <p><b>本字段是 03-02 §9.3 响应字段表未列的增补</b>：PRD FR-4 规则 6 要求撤销确认弹窗
     * 展示「其中学员 M 名」、规则 7 要求日志含学员数，而分册的响应里<b>没有承载它的字段</b>。
     * 不加就是那两条规则都无处落地。增补是<b>附加</b>不是修改，前端不读它也不会坏。已登记。
     */
    private Integer affectedStudentCount;

    private List<CascadeDetailVO> cascadeDetail = new ArrayList<>();

    /** 恒为 {@code true} —— 明示学习进度 / 答卷 / 错题本等记录<b>已保留未删</b>。 */
    private Boolean learningRecordsRetained;

    private LocalDateTime revokeTime;

    public Integer getResourceType() {
        return resourceType;
    }

    public void setResourceType(Integer resourceType) {
        this.resourceType = resourceType;
    }

    public Integer getResourceCount() {
        return resourceCount;
    }

    public void setResourceCount(Integer resourceCount) {
        this.resourceCount = resourceCount;
    }

    public Integer getTargetNodeCount() {
        return targetNodeCount;
    }

    public void setTargetNodeCount(Integer targetNodeCount) {
        this.targetNodeCount = targetNodeCount;
    }

    public Integer getRevokedCount() {
        return revokedCount;
    }

    public void setRevokedCount(Integer revokedCount) {
        this.revokedCount = revokedCount;
    }

    public Integer getDirectRevokedCount() {
        return directRevokedCount;
    }

    public void setDirectRevokedCount(Integer directRevokedCount) {
        this.directRevokedCount = directRevokedCount;
    }

    public Integer getCascadeRevokedCount() {
        return cascadeRevokedCount;
    }

    public void setCascadeRevokedCount(Integer cascadeRevokedCount) {
        this.cascadeRevokedCount = cascadeRevokedCount;
    }

    public Integer getAffectedNodeCount() {
        return affectedNodeCount;
    }

    public void setAffectedNodeCount(Integer affectedNodeCount) {
        this.affectedNodeCount = affectedNodeCount;
    }

    public Integer getAffectedStudentCount() {
        return affectedStudentCount;
    }

    public void setAffectedStudentCount(Integer affectedStudentCount) {
        this.affectedStudentCount = affectedStudentCount;
    }

    public List<CascadeDetailVO> getCascadeDetail() {
        return cascadeDetail;
    }

    public void setCascadeDetail(List<CascadeDetailVO> cascadeDetail) {
        this.cascadeDetail = cascadeDetail == null ? new ArrayList<>() : cascadeDetail;
    }

    public Boolean getLearningRecordsRetained() {
        return learningRecordsRetained;
    }

    public void setLearningRecordsRetained(Boolean learningRecordsRetained) {
        this.learningRecordsRetained = learningRecordsRetained;
    }

    public LocalDateTime getRevokeTime() {
        return revokeTime;
    }

    public void setRevokeTime(LocalDateTime revokeTime) {
        this.revokeTime = revokeTime;
    }
}
