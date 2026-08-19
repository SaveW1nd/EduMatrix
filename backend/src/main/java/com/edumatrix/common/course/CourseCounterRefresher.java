package com.edumatrix.common.course;

/**
 * 课程冗余计数的<b>唯一</b>维护入口（PRD F2-1 规则 7、契约 §4 {@code crs_course}
 * 的 {@code lesson_count} / {@code total_duration}）。
 *
 * <h2>为什么模块 09 必须经过它，而不是自己 {@code UPDATE crs_lesson}</h2>
 * <p>模块 09 规则 9 逐字：「冗余刷新（<b>引用课时的 {@code duration}</b>、
 * 所属课程的 {@code total_duration}）必须另发异步任务」。那个异步任务要写
 * {@code crs_lesson.duration} —— 而 {@code crs_lesson} 是模块 08 的表。
 * 让模块 09 直写等于把「课时时长怎么算」实现两遍，而两份同源实现迟早写歧；
 * 何况 {@code vod} 领域 import {@code course} 领域会直接命中约定检查③。
 *
 * <p><b>{@code crs_lesson.duration} 与 {@code crs_course.lesson_count} /
 * {@code total_duration} 全库只有实现类一处写入点。</b>
 * 改这里必须同时想清楚另一处；新增第二处即为缺陷。
 *
 * <p>到期标记：本接口的调用义务已写进 {@code 04-实施计划.md} §B 模块 09 的
 * 「做完什么算做完」。
 */
public interface CourseCounterRefresher {

    /**
     * 全量重算某课程的两个冗余列。
     *
     * <p><b>全量重算而不是增量 ±1</b>：本项目已被增量计数坑过两次
     * （{@code org_node.child_count/student_count}、{@code org_teacher.student_count}）。
     * 增量的失效是<b>永久漂移</b>且不报错；全量重算的失效在下一次任何课时变更时自愈。
     */
    void refreshByCourse(Long courseId);

    /**
     * 视频时长变化时：把引用该媒资的全部课时的 {@code duration} 刷成新值，
     * 再对涉及到的每个课程调 {@link #refreshByCourse}。
     *
     * @param videoId     媒资 ID
     * @param newDuration 新时长（秒）
     * @return 受影响的课时数
     */
    int refreshByVideo(Long videoId, Integer newDuration);
}
