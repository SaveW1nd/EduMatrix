package com.edumatrix.question.bank.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 接口 9 版本列表的行（03-04 §2.5）—— <b>不含完整 content</b>，
 * 要完整快照走接口 10。
 *
 * <p>⚠ PRD F3-2 规则 5 写「提供版本历史查看：<b>任意两版本 diff 展示</b>
 * （题干/选项/答案变化高亮）」，而 12 个接口里<b>没有任何接口承载 diff</b>。
 * 已登记 F-77：前端拉两次接口 10 自行比对；不新增接口（接口总数仍 161）。
 */
public class QuestionVersionMetaVO {

    private Integer version;
    private Boolean isCurrent;
    private String stemPreview;
    private BigDecimal scoreDefault;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdTime;

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Boolean getIsCurrent() {
        return isCurrent;
    }

    public void setIsCurrent(Boolean isCurrent) {
        this.isCurrent = isCurrent;
    }

    public String getStemPreview() {
        return stemPreview;
    }

    public void setStemPreview(String stemPreview) {
        this.stemPreview = stemPreview;
    }

    public BigDecimal getScoreDefault() {
        return scoreDefault;
    }

    public void setScoreDefault(BigDecimal scoreDefault) {
        this.scoreDefault = scoreDefault;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }
}
