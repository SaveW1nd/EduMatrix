package com.edumatrix.question.bank.dto;

/**
 * 接口 5 分页查询题目（03-04 §2.1）。
 *
 * <p><b>材料题只出父题</b>：固定过滤 {@code parent_id = 0}，子题不在列表中单独出现，
 * 子题内容通过题目详情（接口 8）随父题返回。
 *
 * <p><b>没有 {@code ownerNodeId} 参数</b>：理由与 {@code CoursePageQuery} 逐字同构 ——
 * 按 03-04 §0.1 的可见性口径（{@code owner_node_id} <b>精确等于</b>我的节点 ∪
 * 被显式授权给我的节点），这个参数没有任何可用取值。来源筛选用 {@code grantType}。
 */
public class QuestionPageQuery {

    private Integer pageNum;
    private Integer pageSize;
    /** 题型 1~6。 */
    private Integer questionType;
    /** 难度 1~5。 */
    private Integer difficulty;
    /** 分类 ID，<b>含其全部子孙分类</b>下的题目（03-04 §2.1）。 */
    private Long categoryId;
    /** 关键词，模糊匹配 {@code stem_preview}。 */
    private String keyword;
    /** 0 草稿 1 启用 2 停用；不传查全部。 */
    private Integer status;
    /** 来源筛选：1 仅自有 2 仅被授权；不传返回两者并集。 */
    private Integer grantType;
    /** 创建人 {@code user_id}（对应公共字段 {@code create_by}，全库不设 creator_id 专用列）。 */
    private Long creatorId;

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
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

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
}
