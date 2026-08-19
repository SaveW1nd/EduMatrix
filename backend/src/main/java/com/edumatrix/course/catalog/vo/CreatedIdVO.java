package com.edumatrix.course.catalog.vo;

/** 创建类接口的统一响应体 {@code {"id": "..."}}（03-03 §1.3 / §2.2 / §3.3 / §4.3）。 */
public class CreatedIdVO {

    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
