package com.edumatrix.org.grant.vo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 接口 38 授权资源给节点的响应（03-02 §9.2 响应示例逐字段）。
 *
 * <h2>{@code effectiveValidEnd} 为什么可能与某些行的实际值不同</h2>
 * <p>契约 §2.5 规则 7 的截断上界是「<b>授权人自身对该资源的</b> {@code valid_end}」——
 * <b>逐个资源各有各的上界</b>。一次授 3 门课、我对它们的到期日各不相同时，
 * 落库的 3 行 {@code valid_end} 就可能是 3 个值，而响应只有一个字段。
 *
 * <p>处置：<b>逐行按各自的上界截断</b>（那才是规则 7 要的，统一取最小会把本来
 * 不必收紧的行也收紧 —— 那是另一种错），{@code effectiveValidEnd} 回<b>最严的那个</b>，
 * {@code validEndTruncated} 只要有任意一行被截断即为 {@code true}。
 * 逐行的真实值经接口 41 可查。已登记（需方可推翻）。
 */
public class GrantCreatedVO {

    private Integer resourceType;
    private Integer resourceCount;
    private Integer targetNodeCount;

    /** 本次<b>新增</b>的授权行数。 */
    private Integer grantedCount;

    /** 因已存在而跳过的组合数（仅 {@code ignoreDuplicate = true} 时可能非 0）。 */
    private Integer duplicatedCount;

    /** 跳过明细，最多前 100 条。 */
    private List<DuplicatedGrantVO> duplicated = new ArrayList<>();

    private LocalDateTime validStart;

    /** 请求里的失效时间（原值，未截断）。 */
    private LocalDateTime validEnd;

    /** 是否有任意一行因超出授权人自身有效期而被截断。 */
    private Boolean validEndTruncated;

    /** 实际落库的失效时间中<b>最严</b>的那个；全部永久时为 {@code null}。 */
    private LocalDateTime effectiveValidEnd;

    private Integer grantSource;
    private LocalDateTime grantTime;

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

    public Integer getGrantedCount() {
        return grantedCount;
    }

    public void setGrantedCount(Integer grantedCount) {
        this.grantedCount = grantedCount;
    }

    public Integer getDuplicatedCount() {
        return duplicatedCount;
    }

    public void setDuplicatedCount(Integer duplicatedCount) {
        this.duplicatedCount = duplicatedCount;
    }

    public List<DuplicatedGrantVO> getDuplicated() {
        return duplicated;
    }

    public void setDuplicated(List<DuplicatedGrantVO> duplicated) {
        this.duplicated = duplicated == null ? new ArrayList<>() : duplicated;
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

    public Integer getGrantSource() {
        return grantSource;
    }

    public void setGrantSource(Integer grantSource) {
        this.grantSource = grantSource;
    }

    public LocalDateTime getGrantTime() {
        return grantTime;
    }

    public void setGrantTime(LocalDateTime grantTime) {
        this.grantTime = grantTime;
    }
}
