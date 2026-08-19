package com.edumatrix.course.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接口 8 创建章节（03-03 §2.2）。
 *
 * <p>{@code parentId = 0} 创建「章」；否则必须指向 {@code courseId} 课程内
 * {@code parent_id = 0} 的章（<b>不允许节下再建节</b>），违反 → {@code 20006}。
 */
public class ChapterCreateReq {

    @NotNull(message = "不能为空")
    private Long courseId;

    /** {@code "0"} 表示章，否则为本课程某章 ID（表示节）。 */
    @NotNull(message = "不能为空")
    private Long parentId;

    @NotBlank(message = "不能为空")
    @Size(min = 1, max = 100, message = "长度须为 1~100 字符")
    private String chapterName;

    /** 同级排序号，从 1 起；不传时自动排末尾。 */
    private Integer sort;

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getChapterName() {
        return chapterName;
    }

    public void setChapterName(String chapterName) {
        this.chapterName = chapterName;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
