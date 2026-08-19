package com.edumatrix.course.catalog.dto;

import jakarta.validation.constraints.NotNull;

/** 接口 12 课时分页列表（03-03 §3.1）。{@code courseId} 必填。 */
public class LessonPageQuery {

    private Integer pageNum;
    private Integer pageSize;

    @NotNull(message = "不能为空")
    private Long courseId;

    /** 所属章节 ID（章或节）。 */
    private Long chapterId;
    private String lessonName;
    /** 1 视频 2 图文。 */
    private Integer lessonType;
    /** 0 隐藏 1 可见。 */
    private Integer status;

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

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Long getChapterId() {
        return chapterId;
    }

    public void setChapterId(Long chapterId) {
        this.chapterId = chapterId;
    }

    public String getLessonName() {
        return lessonName;
    }

    public void setLessonName(String lessonName) {
        this.lessonName = lessonName;
    }

    public Integer getLessonType() {
        return lessonType;
    }

    public void setLessonType(Integer lessonType) {
        this.lessonType = lessonType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
