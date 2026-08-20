package com.edumatrix.org.grant.vo;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 接口 37 我可授权的资源列表的一行（03-02 §9.1 响应示例逐字段）。
 *
 * <p><b>{@code validStart} / {@code validEnd} 是「我自己持有该资源的有效期」</b>
 *（§9.1 响应字段说明逐字）：{@code source = 2} 时取 {@code org_resource_grant}；
 * {@code source = 1} 自有资源为 {@code null}，表示<b>永久</b>。
 * 不是「这次要授出去的有效期」—— 那个由调用方在接口 38 里指定。
 */
public class GrantableResourceVO {

    private Integer resourceType;
    private Long resourceId;
    private String resourceName;
    private Long ownerNodeId;
    private String ownerNodeName;

    /** 1 自有（永久可授出） 2 受授权（自身授权到期后即从本列表消失）。 */
    private Integer source;

    private LocalDateTime validStart;
    private LocalDateTime validEnd;

    /** 按资源类型不同的扩展信息（§9.1 {@code extra}）。 */
    private Map<String, Object> extra;

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

    public Long getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(Long ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

    public String getOwnerNodeName() {
        return ownerNodeName;
    }

    public void setOwnerNodeName(String ownerNodeName) {
        this.ownerNodeName = ownerNodeName;
    }

    public Integer getSource() {
        return source;
    }

    public void setSource(Integer source) {
        this.source = source;
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

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }
}
