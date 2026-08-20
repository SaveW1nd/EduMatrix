package com.edumatrix.org.grant.vo;

/**
 * 接口 38 响应里 {@code duplicated[]} 的一行 —— 因已存在而被跳过的组合。
 *
 * <p><b>{@code existingValidEnd} 已删除</b>：它存在的唯一理由是让运营判断
 * 「要不要先撤销再重授」，而需方 2026-08-21 定案取消有效期后，重复授权只有一种含义
 *（已经授过了），没有第二种可能需要比较。<b>最多返回前 100 条</b>（§9.2 响应字段说明）。
 */
public class DuplicatedGrantVO {

    private Long resourceId;
    private String resourceName;
    private Long targetNodeId;
    private String targetNodeName;

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
