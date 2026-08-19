package com.edumatrix.question.category.vo;

/** 接口 2 新增题库分类的响应体 {@code {"id": "..."}}（03-04 §1.2）。 */
public class CategoryCreatedVO {

    private Long id;

    public CategoryCreatedVO() {
    }

    public CategoryCreatedVO(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
