package com.edumatrix.course.catalog.mapper;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.course.catalog.entity.CrsLesson;

/**
 * {@code crs_lesson}。租户条件由插件注入（契约 §2.9）。
 *
 * <p>逻辑删除一律用 {@code UNIX_TIMESTAMP(NOW(3)) * 1000} 写 {@code deleted_at}
 * —— 与 {@code common/entity/BaseEntity} 上 {@code @TableLogic} 的 {@code delval}
 * <b>逐字相同</b>。批量删除走这里的自定义 SQL 而不是逐行 {@code deleteById}，
 * 是为了让「删章节 → 级联删课时」在一条语句内完成、且与冗余重算落在同一事务里。
 */
@Mapper
public interface CrsLessonMapper extends BaseMapper<CrsLesson> {

    /** 章节维度的直挂课时数（03-03 §2.1 每个节点的 {@code lessonCount}）。 */
    @Select("<script>"
            + "SELECT chapter_id AS chapterId, COUNT(*) AS lessonCount FROM crs_lesson "
            + " WHERE deleted_at = 0 AND chapter_id IN "
            + " <foreach collection='chapterIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach>"
            + " GROUP BY chapter_id"
            + "</script>")
    List<ChapterLessonCount> countByChapters(@Param("chapterIds") Collection<Long> chapterIds);

    /**
     * 级联逻辑删除某批章节下的全部未删除课时（03-03 §2.4）。
     *
     * <p>{@code deleted_at} 的写法与 {@code @TableLogic} 的 {@code delval} 逐字一致；
     * 两处不一致会让同一条业务规则产生两种删除标记，而按 {@code deleted_at = 0} 的查询
     * 对两者都成立 —— 差异不会报错，只会在唯一索引与审计口径上出问题。
     */
    @Update("<script>"
            + "UPDATE crs_lesson SET deleted_at = UNIX_TIMESTAMP(NOW(3)) * 1000, update_by = #{updateBy} "
            + " WHERE deleted_at = 0 AND chapter_id IN "
            + " <foreach collection='chapterIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach>"
            + "</script>")
    int softDeleteByChapters(@Param("chapterIds") Collection<Long> chapterIds,
                             @Param("updateBy") Long updateBy);

    /** 删除课程时级联逻辑删除其下全部课时（03-03 §1.5）。 */
    @Update("UPDATE crs_lesson SET deleted_at = UNIX_TIMESTAMP(NOW(3)) * 1000, update_by = #{updateBy} "
            + " WHERE deleted_at = 0 AND course_id = #{courseId}")
    int softDeleteByCourse(@Param("courseId") Long courseId, @Param("updateBy") Long updateBy);

    /**
     * 视频时长变化时刷新引用该媒资的全部课时（模块 09 规则 9，经
     * {@code common/course/CourseCounterRefresher#refreshByVideo}）。走 {@code idx_video_id}。
     */
    @Update("UPDATE crs_lesson SET duration = #{duration} "
            + " WHERE deleted_at = 0 AND video_id = #{videoId} AND lesson_type = 1")
    int updateDurationByVideo(@Param("videoId") Long videoId, @Param("duration") Integer duration);

    /** 引用该媒资的未删除课时所属的课程 id（去重）。{@code refreshByVideo} 用它决定重算哪些课程。 */
    @Select("SELECT DISTINCT course_id FROM crs_lesson "
            + " WHERE deleted_at = 0 AND video_id = #{videoId} AND lesson_type = 1")
    List<Long> selectCourseIdsByVideo(@Param("videoId") Long videoId);

    /**
     * 上架前置校验（03-03 §1.6 规则 2 后半句）：
     * <b>全部视频课时</b>关联视频 {@code status} 不为 2 的第一条。
     *
     * <p>返回课时名与视频状态，供 {@code 20003} 的失败文案点名到具体课时
     * （§1.6 失败响应示例逐字：「课时[1.1.2 集合的表示（视频）]关联视频尚未转码完成」）。
     *
     * <p><b>这里不能 join {@code vod_video}</b> —— {@code course} 领域不读别的领域的表，
     * 且模块 09 之前 {@code vod} 领域的读取一律经 {@code common/media/VideoRefReader}。
     * 故只取 {@code (id, lesson_name, video_id)}，状态判定在 Service 层做。
     */
    @Select("SELECT id, lesson_name AS lessonName, video_id AS videoId FROM crs_lesson "
            + " WHERE deleted_at = 0 AND course_id = #{courseId} AND lesson_type = 1 "
            + " ORDER BY sort ASC, id ASC")
    List<VideoLessonRow> selectVideoLessons(@Param("courseId") Long courseId);

    /** {@link #countByChapters} 的行。 */
    class ChapterLessonCount {
        private Long chapterId;
        private Integer lessonCount;

        public Long getChapterId() {
            return chapterId;
        }

        public void setChapterId(Long chapterId) {
            this.chapterId = chapterId;
        }

        public Integer getLessonCount() {
            return lessonCount;
        }

        public void setLessonCount(Integer lessonCount) {
            this.lessonCount = lessonCount;
        }
    }

    /** {@link #selectVideoLessons} 的行。 */
    class VideoLessonRow {
        private Long id;
        private String lessonName;
        private Long videoId;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getLessonName() {
            return lessonName;
        }

        public void setLessonName(String lessonName) {
            this.lessonName = lessonName;
        }

        public Long getVideoId() {
            return videoId;
        }

        public void setVideoId(Long videoId) {
            this.videoId = videoId;
        }
    }
}
