package com.edumatrix.org.grant.vo;

import java.time.LocalDateTime;

/**
 * 接口 40 修改授权有效期的响应（03-02 §9.4 响应示例逐字段）。
 *
 * <p>{@code cascadeTruncatedCount} 明示<b>因本次缩短而被连带截断的子树授权行数</b>；
 * <b>延长时恒为 0</b>（§9.4 响应字段说明逐字）。
 */
public class GrantValidityUpdatedVO {

    /** 被修改的授权行 ID（{@code org_resource_grant.id}）。 */
    private Long grantId;

    private LocalDateTime validStart;
    private LocalDateTime validEnd;

    /** 请求的 {@code validEnd} 是否因超出授权人自身有效期而被截断。 */
    private Boolean validEndTruncated;

    /** 实际落库的失效时间（截断后的值）。 */
    private LocalDateTime effectiveValidEnd;

    /** 因本次<b>缩短</b>而被连带截断的子树授权行数；延长时恒为 0。 */
    private Integer cascadeTruncatedCount;

    public Long getGrantId() {
        return grantId;
    }

    public void setGrantId(Long grantId) {
        this.grantId = grantId;
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

    public Boolean getValidEndTruncated() {
        return validEndTruncated;
    }

    public void setValidEndTruncated(Boolean validEndTruncated) {
        this.validEndTruncated = validEndTruncated;
    }

    public LocalDateTime getEffectiveValidEnd() {
        return effectiveValidEnd;
    }

    public void setEffectiveValidEnd(LocalDateTime effectiveValidEnd) {
        this.effectiveValidEnd = effectiveValidEnd;
    }

    public Integer getCascadeTruncatedCount() {
        return cascadeTruncatedCount;
    }

    public void setCascadeTruncatedCount(Integer cascadeTruncatedCount) {
        this.cascadeTruncatedCount = cascadeTruncatedCount;
    }
}
