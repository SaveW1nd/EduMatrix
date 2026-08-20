package com.edumatrix.org.grant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 接口 37 我可授权的资源列表（03-02 §9.1）的查询参数。
 *
 * <p>{@code resourceType} <b>必填</b>：一次只查一类资源 —— 课程 / 题目 / 视频的展示字段
 * 差异较大（§9.1 说明段逐字）。非法取值返回 {@code 400}，不是业务码。
 */
public class GrantableResourceQueryReq {

    @NotNull(message = "资源类型不能为空")
    @Min(value = 1, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    @Max(value = 3, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    private Integer resourceType;

    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNum;

    @Min(value = 1, message = "每页条数至少 1")
    @Max(value = 100, message = "每页条数最大 100")
    private Integer pageSize;

    /** 资源名称模糊匹配（课程名 / 题干摘要 / 视频名）。 */
    private String keyword;

    /** 来源筛选：1 自有 2 受授权；不传查全部。 */
    @Min(value = 1, message = "来源只能是 1 自有 / 2 受授权")
    @Max(value = 2, message = "来源只能是 1 自有 / 2 受授权")
    private Integer source;

    /** 科目筛选，仅 {@code resourceType = 1} 课程有效。 */
    private String subject;

    /** 题库分类 ID，仅 {@code resourceType = 2} 题目有效。 */
    private Long categoryId;

    public Integer getResourceType() {
        return resourceType;
    }

    public void setResourceType(Integer resourceType) {
        this.resourceType = resourceType;
    }

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

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getSource() {
        return source;
    }

    public void setSource(Integer source) {
        this.source = source;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }
}
