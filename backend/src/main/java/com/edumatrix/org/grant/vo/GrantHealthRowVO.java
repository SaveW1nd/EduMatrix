package com.edumatrix.org.grant.vo;

import java.time.LocalDateTime;

/**
 * 接口 51 授权健康度巡检结果的一行（03-02 §9.6 响应示例逐字段）。
 */
public class GrantHealthRowVO {

    private Integer resourceType;
    private Long resourceId;
    private String resourceName;
    private Long targetNodeId;
    private String targetNodeName;

    /**
     * {@code type=dangling} 时 = <b>授权链上已失去该资源的那个上级节点</b>；
     * {@code type=crossScope} 时 = <b>移动后新祖先链上无人持有该资源的那个上级</b>
     *（即「该补授给谁」）；{@code type=expiring} 时为 {@code null}（§9.6 字段说明逐字）。
     */
    private Long missingNodeId;

    private String missingNodeName;

    private LocalDateTime validEnd;

    /**
     * 本行由<b>哪一轮巡检</b>发现。
     *
     * <p><b>F-83 定案下它的准确含义</b>：巡检结果不落快照、接口实时算，
     * 所以这里回的是<b>该租户最近一轮巡检的完成时刻</b>，
     * 不是「这一行是那一轮第一次出现的」。从未巡检过时为 {@code null}。
     * 已登记为与分册字面的一处偏差。
     */
    private LocalDateTime detectedTime;

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

    public Long getMissingNodeId() {
        return missingNodeId;
    }

    public void setMissingNodeId(Long missingNodeId) {
        this.missingNodeId = missingNodeId;
    }

    public String getMissingNodeName() {
        return missingNodeName;
    }

    public void setMissingNodeName(String missingNodeName) {
        this.missingNodeName = missingNodeName;
    }

    public LocalDateTime getValidEnd() {
        return validEnd;
    }

    public void setValidEnd(LocalDateTime validEnd) {
        this.validEnd = validEnd;
    }

    public LocalDateTime getDetectedTime() {
        return detectedTime;
    }

    public void setDetectedTime(LocalDateTime detectedTime) {
        this.detectedTime = detectedTime;
    }
}
