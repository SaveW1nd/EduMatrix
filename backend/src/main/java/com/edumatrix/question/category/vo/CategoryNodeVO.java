package com.edumatrix.question.category.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类树节点（03-04 §1.1）。
 *
 * <p>{@code questionCount} 为该节点<b>直属</b>题目数（不含子孙节点，材料题只计父题）
 * —— 03-04 §1.1 的脚注逐字。
 */
public class CategoryNodeVO {

    private Long id;
    private Long parentId;
    private String categoryName;
    private Integer sort;
    private Integer questionCount;
    private List<CategoryNodeVO> children = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Integer getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }

    public List<CategoryNodeVO> getChildren() {
        return children;
    }

    public void setChildren(List<CategoryNodeVO> children) {
        this.children = children;
    }
}
