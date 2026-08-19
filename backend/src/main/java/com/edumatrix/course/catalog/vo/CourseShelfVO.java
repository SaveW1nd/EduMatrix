package com.edumatrix.course.catalog.vo;

/** 接口 6 课程上下架的响应（03-03 §1.6）。 */
public class CourseShelfVO {

    private Long id;

    private Integer status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
