package com.edumatrix.org.grant.vo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 接口 38 授权资源给节点的响应（03-02 §9.2 响应示例逐字段）。
 *
 * <h2>四个有效期字段<b>已删除</b>，不是恒空</h2>
 * <p>需方 2026-08-21 定案「授权一律永久有效」之后，{@code validStart} /
 * {@code validEnd} / {@code validEndTruncated} / {@code effectiveValidEnd}
 * 全部失去了对象：契约 §2.5 规则 7 的截断上界（「不晚于授权人自身对该资源的
 * {@code valid_end}」）已无适用对象，截断逻辑随之删除。
 *
 * <p><b>为什么这四个是删而不是留成恒空</b>：接口 41 的 {@code validEnd} 需方点名
 * 「恒为 null」保留（它是一条<b>事实</b>字段，恒空只是说「没有到期日」）；
 * 而这四个是<b>截断动作的产物</b>，动作没了字段就是纯噪音 ——
 * 一个永远为 {@code false} 的 {@code validEndTruncated} 只会让下一个人去找那段不存在的逻辑。
 * F-92（「一个响应字段装不下逐行不同的日期」）已标注<b>已随取消有效期而消失</b>，
 * 正文原样保留：两年后有人做「试听一个月」时需要知道上次是怎么翻车的。
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
