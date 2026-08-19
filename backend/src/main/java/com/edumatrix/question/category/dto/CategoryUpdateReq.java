package com.edumatrix.question.category.dto;

import jakarta.validation.constraints.Size;

/**
 * 接口 3 修改题库分类（03-04 §1.3）。四个字段全部可选，只改传了的。
 *
 * <p>{@code parentId} 用于移动分类：<b>不可移动到自身或其子孙节点</b>（否则 400）。
 */
public class CategoryUpdateReq {

    @Size(min = 1, max = 50, message = "长度须为 1~50 字符")
    private String categoryName;

    private Long parentId;

    private Integer sort;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
