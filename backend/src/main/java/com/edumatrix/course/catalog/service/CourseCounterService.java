package com.edumatrix.course.catalog.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.edumatrix.common.course.CourseCounterRefresher;
import com.edumatrix.course.catalog.mapper.CrsCourseMapper;
import com.edumatrix.course.catalog.mapper.CrsLessonMapper;

/**
 * {@code crs_course.lesson_count} / {@code total_duration} 与 {@code crs_lesson.duration}
 * 的<b>唯一写入点</b>（{@code common/course/CourseCounterRefresher} 的实现）。
 *
 * <h2>全量重算，不做增量 ±1</h2>
 * <p>本项目已被增量计数坑过两次（{@code org_node.child_count/student_count}、
 * {@code org_teacher.student_count}）。两种失效方式差别是决定性的：
 * <b>增量漏一次就永久漂移且不报错</b>；<b>全量重算漏一次，下一次任何课时变更就自愈</b>。
 * 代价是一次 {@code COUNT} + 一次 {@code SUM}，走 {@code idx_course_status} 前缀，
 * 课时数量级是百级。
 *
 * <h2>口径（C 定案）：全部未删除课时，不加 {@code status} 限定</h2>
 * <p>按 DDL 与 02-数据库设计 §4.2.1 的字面 —— 两处的列注释都是
 * 「课时总数（冗余计数，课时增删时同步维护）」，<b>没有 status 限定</b>。
 * <b>学生端 03-03 §6.1 的 {@code lessonCount} 不读这一列</b>，
 * 而是现算可见数（{@code status=1} 且未删除），与同一响应里 {@code progressPercent}
 * 的分母同源（§6.1 说明把那条分母写死了）。两个数分属两处、各自不说谎。
 * <b>模块 14 实现 §6.1 时不要图省事读这一列。</b>
 *
 * <h2>同事务，不是异步</h2>
 * <p>03-03 §3.3 规则 4 与 §3.5 写的是「服务端<b>异步</b>重算」，
 * 而同一分册的 §2.4（删除章节）写的是「<b>同步</b>维护课程 {@code lesson_count}、
 * {@code total_duration}」—— <b>分册内部打架</b>。本轮按「同事务」订正 §3.3/§3.5：
 * 异步重算一旦任务失败，冗余值与真实值不一致而<b>接口返回 200、字段齐全、结果错</b>，
 * 且这种不一致在请求内无法验证 —— 正是本项目 1 号失败模式。
 *
 * <p><b>唯一的例外是模块 09 的转码事件</b>：那里必须异步（模块 09 规则 9：
 * 扇出会拉长单条消息处理时间进而逼高不可见时长），但异步的是<b>调用时机</b>，
 * 本类内部仍是一个事务。
 */
@Service
public class CourseCounterService implements CourseCounterRefresher {

    private final CrsCourseMapper courseMapper;
    private final CrsLessonMapper lessonMapper;

    public CourseCounterService(CrsCourseMapper courseMapper, CrsLessonMapper lessonMapper) {
        this.courseMapper = courseMapper;
        this.lessonMapper = lessonMapper;
    }

    /**
     * {@link Propagation#REQUIRED}：本模块的调用方都已在事务里，重算与课时变更
     * <b>必须落在同一个事务</b>，否则会出现「课时删了、计数没跟上」的中间态被别人读到。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshByCourse(Long courseId) {
        if (courseId != null) {
            courseMapper.recountRedundant(courseId);
        }
    }

    /**
     * 视频时长变化（模块 09 规则 9 的异步刷新任务调它）。
     *
     * <p><b>模块 09 不得自己 {@code UPDATE crs_lesson}</b> —— 那是第二份实现，
     * 且 {@code vod} 领域 import {@code course} 领域会直接命中约定检查③。
     * 理由与到期标记见 {@code common/course/CourseCounterRefresher}。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int refreshByVideo(Long videoId, Integer newDuration) {
        if (videoId == null || newDuration == null) {
            return 0;
        }
        // 先取课程集合再改时长：改完了再查也一样，但先查能少一次「刚被删的课时」竞态
        List<Long> courseIds = lessonMapper.selectCourseIdsByVideo(videoId);
        int affected = lessonMapper.updateDurationByVideo(videoId, newDuration);
        for (Long courseId : courseIds) {
            courseMapper.recountRedundant(courseId);
        }
        return affected;
    }
}
