package com.edumatrix.course.catalog.vo;

/**
 * 接口 10 删除章节的响应（03-03 §2.4）。
 *
 * <p><b>这两个数是「已经删了多少」的回显，不是删除前的预估。</b>
 * §2.4 原写「响应返回受影响课时数，供前端在<b>删除前</b>二次确认弹窗展示」——
 * 响应在删除之后，时序上不可能。删除前的二次确认由前端用接口 7（章节树查询）
 * 每个节点的 {@code lessonCount} 汇总得出；本轮已订正该处措辞。
 * <b>不新增预检接口</b>，接口总数仍为 161。
 */
public class ChapterDeleteVO {

    /** 本次逻辑删除的章节节点数（含自身与其下的节）。 */
    private Integer deletedChapterCount;

    /** 级联逻辑删除的课时数。 */
    private Integer deletedLessonCount;

    public Integer getDeletedChapterCount() {
        return deletedChapterCount;
    }

    public void setDeletedChapterCount(Integer deletedChapterCount) {
        this.deletedChapterCount = deletedChapterCount;
    }

    public Integer getDeletedLessonCount() {
        return deletedLessonCount;
    }

    public void setDeletedLessonCount(Integer deletedLessonCount) {
        this.deletedLessonCount = deletedLessonCount;
    }
}
