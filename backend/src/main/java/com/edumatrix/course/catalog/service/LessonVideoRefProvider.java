package com.edumatrix.course.catalog.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.course.LessonVideoRefCounter;
import com.edumatrix.course.catalog.entity.CrsLesson;
import com.edumatrix.course.catalog.mapper.CrsLessonMapper;

/**
 * {@link LessonVideoRefCounter} 的实现 —— 薄委派，<b>不含任何判定</b>（05-工程结构.md §E1 纪律 1）。
 *
 * <p>「计数 &gt; 0 该返 {@code 20016} 还是只是提示影响面」是调用方（模块 09）的事，
 * 本类只回答数字。
 *
 * <p>过滤条件三个，缺一不可：
 * <ul>
 *   <li>{@code deleted_at = 0} —— 由 {@code @TableLogic} 自动注入。
 *       03-03 §7.4 逐字是「存在<b>未删除</b>课时引用时拒绝」，
 *       算上已删课时会让一个本可删的媒资永远删不掉；</li>
 *   <li>{@code lesson_type = 1} —— 图文课时的 {@code video_id} 恒为 NULL，
 *       带上它是为了走 {@code idx_video_id} 之后少一次回表判断，语义上不影响结果；</li>
 *   <li>租户条件由插件注入 —— <b>不手写</b>（契约 §2.9）。</li>
 * </ul>
 */
@Component
public class LessonVideoRefProvider implements LessonVideoRefCounter {

    /** {@code crs_lesson.lesson_type = 1 视频}（契约 §5 {@code lesson_type}）。 */
    private static final int LESSON_TYPE_VIDEO = 1;

    private final CrsLessonMapper lessonMapper;

    public LessonVideoRefProvider(CrsLessonMapper lessonMapper) {
        this.lessonMapper = lessonMapper;
    }

    @Override
    public int countByVideo(Long videoId) {
        if (videoId == null) {
            return 0;
        }
        Long count = lessonMapper.selectCount(new LambdaQueryWrapper<CrsLesson>()
                .eq(CrsLesson::getVideoId, videoId)
                .eq(CrsLesson::getLessonType, LESSON_TYPE_VIDEO));
        return count == null ? 0 : count.intValue();
    }

    @Override
    public Map<Long, Integer> countByVideos(Collection<Long> videoIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (videoIds == null || videoIds.isEmpty()) {
            return counts;
        }
        // 去重后逐个数：本页最多 100 个 id（PageResult 上限），每次都走 idx_video_id 的点查。
        // 【不用 GROUP BY 一次拿回】那需要一条手写 SQL，而 selectCount 走的是插件注入的
        // 租户条件——手写 SQL 就得自己拼 tenant_id，那正是契约 §2.9 明令禁止的形态
        for (Long videoId : new LinkedHashSet<>(videoIds)) {
            int count = countByVideo(videoId);
            if (count > 0) {
                // 「查不到的 id 不出现在结果里」——与 VideoRefReader#readAll 同一约定
                counts.put(videoId, count);
            }
        }
        return counts;
    }
}
