package com.edumatrix.common.course;

import java.util.Collection;
import java.util.Map;

/**
 * 「这个媒资被几个<b>未删除</b>课时引用」—— 跨领域 SPI，接口在 {@code common/}、
 * 实现是 {@code course/catalog} 里的薄委派（05-工程结构.md §E1）。
 *
 * <h2>为什么必须是 SPI，不能让 {@code vod} 直接读 {@code crs_lesson}</h2>
 * <p>约定检查③：领域包之间不得互相 import。{@code vod} 领域 import
 * {@code course/catalog/mapper/CrsLessonMapper} 会直接命中它。
 * 04-实施计划.md §B 模块 09 的「涉及表」写的是「<b>只读</b> {@code crs_lesson}」——
 * <b>读侧同样要走接口</b>，「只读」说的是不写，不是可以直连。
 *
 * <p>而 {@code common/course} 现有的两个接口都答不了这个问题：
 * {@code CourseCounterRefresher} 是<b>写</b>侧（刷冗余计数），
 * {@code LessonVisibilityChecker} 是学生端可见性判定。故新增本接口（<b>F-62</b>）。
 *
 * <h2>两个消费点</h2>
 * <ol>
 *   <li>03-03 §7.3 媒资列表与 §7.6 禁用/启用响应里的 {@code refLessonCount}
 *       —— 给前端在禁用确认弹窗里提示影响面；</li>
 *   <li>03-03 §7.4 删除媒资的拦截：被未删除课时引用时拒绝，返回 {@code 20016}
 *       （PRD F2-3 规则 6）。</li>
 * </ol>
 *
 * <p><b>实现方不做任何判定</b>（§E1 纪律 1）：只回答数字，「大于 0 该返什么码」是调用方的事。
 */
public interface LessonVideoRefCounter {

    /** 单个媒资被多少个未删除课时引用。 */
    int countByVideo(Long videoId);

    /**
     * 批量。<b>查不到的 id 不出现在结果里</b>（调用方按「缺席即 0」处理）——
     * 与 {@code common/media/VideoRefReader#readAll} 同一约定，避免两个 SPI 两种脾气。
     */
    Map<Long, Integer> countByVideos(Collection<Long> videoIds);
}
