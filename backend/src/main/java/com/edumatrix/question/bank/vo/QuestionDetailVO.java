package com.edumatrix.question.bank.vo;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 接口 8 题目详情（03-04 §2.4）。默认返回 {@code current_version} 对应的版本内容
 * （<b>含答案与解析 —— 教师侧接口</b>）。
 *
 * <p>材料题传父题 ID 时附全部子题当前版本；传子题 ID 时返回子题并附 {@code parentId}。
 * {@code childQuestions} 用 {@code NON_NULL}：普通题的响应里根本不出现这个键，
 * 而不是出现一个 {@code null}。
 */
public class QuestionDetailVO {

    private Long id;
    private Long categoryId;
    private String categoryName;
    private Integer questionType;
    private Long parentId;
    private Integer difficulty;
    private Integer currentVersion;
    private Integer status;
    private Long ownerNodeId;
    private String ownerNodeName;
    private Integer grantType;
    private Long creatorId;
    private String creatorName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private QuestionVersionVO version;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<QuestionDetailVO> childQuestions;

    /** 子题在父题内的顺序，从 1 起；取自父题版本 {@code content.childOrder} 的下标。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer sort;

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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
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

    public QuestionVersionVO getVersion() {
        return version;
    }

    public void setVersion(QuestionVersionVO version) {
        this.version = version;
    }

    public List<QuestionDetailVO> getChildQuestions() {
        return childQuestions;
    }

    public void setChildQuestions(List<QuestionDetailVO> childQuestions) {
        this.childQuestions = childQuestions;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
