package com.edumatrix.course.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接口 9 修改章节（03-03 §2.3）。
 *
 * <p><b>只改名称</b>：层级调整与排序统一走接口 11（拖拽排序），
 * 避免两处入口引发并发冲突（§2.3 说明）。
 */
public class ChapterUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(min = 1, max = 100, message = "长度须为 1~100 字符")
    private String chapterName;

    public String getChapterName() {
        return chapterName;
    }

    public void setChapterName(String chapterName) {
        this.chapterName = chapterName;
    }
}
