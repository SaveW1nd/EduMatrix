package com.edumatrix.course.catalog.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 接口 7 章节树查询的节点（03-03 §2.1）。
 *
 * <p>{@code data} 是<b>章</b>的数组，<b>节</b>挂在 {@code children} 下，均按 {@code sort} 升序。
 * 树只有两级 —— 节的 {@code children} 恒为空数组（PRD F2-1 规则 1）。
 *
 * <p>{@code lessonCount} 是<b>该节点直挂</b>的课时数（§2.1 响应字段说明），
 * 不是子树汇总。删除章节前的二次确认弹窗由前端对「章 + 其 children」求和得出。
 */
public class ChapterNodeVO {

    private Long id;
    private Long courseId;
    private String chapterName;
    /** {@code 0} 表示章。JSON 中为字符串 {@code "0"}（雪花 ID 一律字符串化）。 */
    private Long parentId;
    private Integer sort;
    /** 见类注释：该节点<b>直挂</b>课时数。 */
    private Integer lessonCount;
    private List<ChapterNodeVO> children = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public String getChapterName() {
        return chapterName;
    }

    public void setChapterName(String chapterName) {
        this.chapterName = chapterName;
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

    public Integer getLessonCount() {
        return lessonCount;
    }

    public void setLessonCount(Integer lessonCount) {
        this.lessonCount = lessonCount;
    }

    public List<ChapterNodeVO> getChildren() {
        return children;
    }

    public void setChildren(List<ChapterNodeVO> children) {
        this.children = children;
    }
}
