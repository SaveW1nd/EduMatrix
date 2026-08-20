package com.edumatrix.org.grant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 接口 41 节点已获授权资源列表（03-02 §9.5）的查询参数。
 *
 * <p>{@code resourceType} <b>选填</b>：不传返回全部类型（与接口 37 相反 ——
 * 那边是「我要授哪一类」，这边是「这个节点手里有什么」）。
 */
public class NodeGrantedResourceQueryReq {

    @Min(value = 1, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    @Max(value = 3, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    private Integer resourceType;

    /** 是否包含已过期授权，默认 {@code false}。置 {@code true} 用于审计。 */
    private Boolean includeExpired;

    private String keyword;

    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNum;

    @Min(value = 1, message = "每页条数至少 1")
    @Max(value = 100, message = "每页条数最大 100")
    private Integer pageSize;

    public boolean includeExpired() {
        return Boolean.TRUE.equals(includeExpired);
    }

    public Integer getResourceType() {
        return resourceType;
    }

    public void setResourceType(Integer resourceType) {
        this.resourceType = resourceType;
    }

    public Boolean getIncludeExpired() {
        return includeExpired;
    }

    public void setIncludeExpired(Boolean includeExpired) {
        this.includeExpired = includeExpired;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
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
}
