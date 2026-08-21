package com.edumatrix.common.course;

/**
 * 课程上架状态读取 SPI —— 接口在 {@code common/}、实现在 {@code course/catalog/}。
 *
 * <p>模块 12 校验链第 4 步「课程已上架」要读 {@code crs_course.status}，而 {@code vod}
 * 领域不得 import {@code course} 领域（检查③）。与同包的 {@code LessonVisibilityChecker} 同构。
 *
 * <p><b>为什么不并进 {@code LessonVisibilityChecker}</b>：那个接口回答的是「这节课时可不可见」，
 * 模块 12 / 13 / 14 共用；课程上架与否是另一个问题，塞进去会让三个模块都被迫关心它。
 */
public interface CourseShelfReader {

    /** 已上架：{@code crs_course.status = 1}。 */
    int STATUS_ON_SHELF = 1;

    /**
     * @return 课程存在、未删除且 {@code status = 1} 时为 {@code true}；
     *         课程不存在或已删除同样返回 {@code false}（调用方按 20013 处理，
     *         与 F-48 定案一致：不为「不存在」单开一个可区分的码）
     */
    boolean isOnShelf(Long courseId);
}
