package com.edumatrix.org.grant.vo;

import java.time.LocalDateTime;

/**
 * 接口 38 响应里 {@code duplicated[]} 的一行 —— 因已存在而被跳过的组合。
 *
 * <p>带 {@code existingValidEnd} 是为了让运营判断「要不要先撤销再重授」。
 * <b>最多返回前 100 条</b>（§9.2 响应字段说明）。
 */
public class DuplicatedGrantVO {

    private Long resourceId;
    private String resourceName;
    private Long targetNodeId;
    private String targetNodeName;

    /** 已有授权的失效时间；{@code null} 表示永久。 */
    private LocalDateTime existingValidEnd;

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

    public LocalDateTime getExistingValidEnd() {
        return existingValidEnd;
    }

    public void setExistingValidEnd(LocalDateTime existingValidEnd) {
        this.existingValidEnd = existingValidEnd;
    }
}
