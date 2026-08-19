package com.edumatrix.course.catalog.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 接口 11 章节拖拽排序（03-03 §2.5）。
 *
 * <p><b>全量提交</b>该课程所有未删除章节的最新结构；服务端在单事务内批量更新。
 * 提交的 id 集合与该课程当前未删除章节集合<b>必须完全一致</b>（不多、不少、不重复），
 * 否则 {@code 20018}。
 */
public class ChapterSortReq {

    @NotEmpty(message = "不能为空")
    @Valid
    private List<ChapterSortItem> chapters;

    public List<ChapterSortItem> getChapters() {
        return chapters;
    }

    public void setChapters(List<ChapterSortItem> chapters) {
        this.chapters = chapters;
    }

    /** 一个章节节点的新位置。 */
    public static class ChapterSortItem {

        @NotNull(message = "不能为空")
        private Long id;

        /** 新父节点：{@code 0} 或本次提交中 {@code parentId = 0} 的节点。 */
        @NotNull(message = "不能为空")
        private Long parentId;

        @NotNull(message = "不能为空")
        private Integer sort;

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

        public Integer getSort() {
            return sort;
        }

        public void setSort(Integer sort) {
            this.sort = sort;
        }
    }
}
