package com.edumatrix.org.grant.vo;

import java.time.LocalDateTime;

/**
 * 接口 41 节点已获授权资源列表的一行（03-02 §9.5 响应示例逐字段）。
 *
 * <p><b>只含该节点被显式授权的资源</b> —— 不回溯祖先链（契约 §2.5 规则 4）。
 * 机构管理员有 100 门课、查其下级管理员的本列表可能只有 30 门，
 * 查某个学生可能只有 3 门：<b>那是正确结果，不是漏了</b>。
 *
 * <h2>{@code expired} 已删除（需方 2026-08-21 定案，F-107）</h2>
 * <p>授权取消有效期后它恒为 {@code false}，而字段注释还写着「仅
 * {@code includeExpired = true} 时可能为 {@code true}」 ——
 * <b>注释描述了一个不存在的行为</b>，本项目命名过的失效模式⑦。
 * {@code validStart} / {@code validEnd} 两个字段是<b>事实</b>（「没有到期日」），
 * 恒为 {@code null} 是它们的真实取值，需方点名保留；
 * 而 {@code expired} 是<b>对那个事实的判断</b>，判断的对象没了，字段就不该在。
 */
public class NodeGrantedResourceVO {

    /** 授权行 ID（{@code org_resource_grant.id}）。 */
    private Long id;

    private Integer resourceType;
    private Long resourceId;
    private String resourceName;
    private Long targetNodeId;
    private String targetNodeName;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;

    private Integer grantSource;
    private String grantSourceName;
    private Long sourceRefId;
    private String sourceRefName;
    private Long grantBy;
    private String grantByName;
    private LocalDateTime grantTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public LocalDateTime getValidStart() {
        return validStart;
    }

    public void setValidStart(LocalDateTime validStart) {
        this.validStart = validStart;
    }

    public LocalDateTime getValidEnd() {
        return validEnd;
    }

    public void setValidEnd(LocalDateTime validEnd) {
        this.validEnd = validEnd;
    }

    public Integer getGrantSource() {
        return grantSource;
    }

    public void setGrantSource(Integer grantSource) {
        this.grantSource = grantSource;
    }

    public String getGrantSourceName() {
        return grantSourceName;
    }

    public void setGrantSourceName(String grantSourceName) {
        this.grantSourceName = grantSourceName;
    }

    public Long getSourceRefId() {
        return sourceRefId;
    }

    public void setSourceRefId(Long sourceRefId) {
        this.sourceRefId = sourceRefId;
    }

    public String getSourceRefName() {
        return sourceRefName;
    }

    public void setSourceRefName(String sourceRefName) {
        this.sourceRefName = sourceRefName;
    }

    public Long getGrantBy() {
        return grantBy;
    }

    public void setGrantBy(Long grantBy) {
        this.grantBy = grantBy;
    }

    public String getGrantByName() {
        return grantByName;
    }

    public void setGrantByName(String grantByName) {
        this.grantByName = grantByName;
    }

    public LocalDateTime getGrantTime() {
        return grantTime;
    }

    public void setGrantTime(LocalDateTime grantTime) {
        this.grantTime = grantTime;
    }
}
