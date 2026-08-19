package com.edumatrix.question.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 接口 2 新增题库分类（03-04 §1.2）。 */
public class CategoryCreateReq {

    /** 父分类 ID，根节点传 {@code "0"}。 */
    @NotNull(message = "不能为空")
    private Long parentId;

    @NotBlank(message = "不能为空")
    @Size(min = 1, max = 50, message = "长度须为 1~50 字符")
    private String categoryName;

    /** 排序号，默认 0，升序。 */
    private Integer sort;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
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
