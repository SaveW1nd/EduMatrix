package com.edumatrix.question.bank.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 接口 5 分页查询题目的行（03-04 §2.1 响应示例）。
 *
 * <p>{@code grantType} 1 自有 / 2 被授权。{@code grantType=2} 的题目为
 * <b>只读可用</b>：可选进作业、可发布，但不可修改/停用/删除。
 *
 * <p>{@code scoreDefault} 来自<b>当前版本行</b>（主表没有这一列），
 * 所以列表查询要一次性把当前版本的建议分捞出来 —— 逐行点查是 N 次往返。
 */
public class QuestionListVO {

    private Long id;
    private Long categoryId;
    private String categoryName;
    private Integer questionType;
    private Integer difficulty;
    private Integer currentVersion;
    private String stemPreview;
    private BigDecimal scoreDefault;
    private Integer status;
    private Long ownerNodeId;
    private String ownerNodeName;
    private Integer grantType;
    private Long creatorId;
    private String creatorName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public Integer getQuestionType() {
        return questionType;
    }

    public void setQuestionType(Integer questionType) {
        this.questionType = questionType;
    }

    public Integer getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Integer difficulty) {
        this.difficulty = difficulty;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Integer currentVersion) {
        this.currentVersion = currentVersion;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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

    public Integer getGrantType() {
        return grantType;
    }

    public void setGrantType(Integer grantType) {
        this.grantType = grantType;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public String getCreatorName() {
        return creatorName;
    }

    public void setCreatorName(String creatorName) {
        this.creatorName = creatorName;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
