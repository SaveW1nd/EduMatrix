package com.edumatrix.course.catalog.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code crs_chapter} 章/节表（03-03 §2，02-数据库设计 §4.2.2）。
 *
 * <p><b>只有两级</b>：{@code parent_id = 0} 是「章」，否则指向本课程内某个章的 id、表示「节」
 * （PRD F2-1 规则 1、03-03 §2 导语）。违反 → {@code 20006}。
 * 「节下再建节」被拒是模块 08 的自检项之一。
 */
@TableName("crs_chapter")
public class CrsChapter extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** {@code parent_id = 0} 表示「章」。API 层以字符串 {@code "0"} 传递（雪花 ID 一律字符串化）。 */
    public static final long ROOT_PARENT_ID = 0L;

    private Long courseId;

    /** {@code 0} = 章；否则为本课程某章的 id = 节。 */
    private Long parentId;

    private String chapterName;

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

    public boolean isTopLevel() {
        return parentId != null && parentId == ROOT_PARENT_ID;
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
