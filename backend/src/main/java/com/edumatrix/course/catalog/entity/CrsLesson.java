package com.edumatrix.course.catalog.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code crs_lesson} 课时表（03-03 §3，02-数据库设计 §4.2.3）。
 *
 * <h2>{@code content_id} 在 API 层叫 {@code materialId}</h2>
 * <p>03-03 §0.4 逐字：「API 中 {@code materialId} 对应 {@code crs_lesson.content_id}
 * （→ {@code crs_material.id}，契约字段名为 {@code content_id}，API 层为语义清晰使用
 * materialId，开发实现时按此映射，<b>不新增表字段</b>）」。
 * 实体按表列命名（05-工程结构 §F2：实体字段与 02-数据库设计 字段表逐列对应），
 * 映射发生在 DTO/VO 层。
 *
 * <p>{@code duration} 对视频课时冗余自 {@code vod_video.duration}，图文课时恒 0
 * （03-03 §3 导语）。写入点只有 {@code CourseCounterService} 与
 * {@code LessonService}，别处不得直写。
 */
@TableName("crs_lesson")
public class CrsLesson extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** {@code lesson_type = 1} 视频。 */
    public static final int TYPE_VIDEO = 1;
    /** {@code lesson_type = 2} 图文。 */
    public static final int TYPE_MATERIAL = 2;

    /** {@code status = 0} 隐藏。 */
    public static final int STATUS_HIDDEN = 0;
    /** {@code status = 1} 可见。 */
    public static final int STATUS_VISIBLE = 1;

    /** 冗余自章节，加速课程维度查询（DDL 注释）。由服务端写，请求体不接受。 */
    private Long courseId;

    private Long chapterId;

    private String lessonName;

    /** {@code 1} 视频 {@code 2} 图文。 */
    private Integer lessonType;

    /** {@code lesson_type=1} 时必填，→ {@code vod_video.id}。 */
    private Long videoId;

    /** {@code lesson_type=2} 时必填，→ {@code crs_material.id}。API 层叫 {@code materialId}。 */
    private Long contentId;

    /** 秒。视频课时冗余自 {@code vod_video.duration}；图文恒 0。 */
    private Integer duration;

    private Integer sort;

    /** {@code 0} 否 {@code 1} 是。 */
    private Integer isFreePreview;

    /** {@code 0} 隐藏 {@code 1} 可见。 */
    private Integer status;

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Long getChapterId() {
        return chapterId;
    }

    public void setChapterId(Long chapterId) {
        this.chapterId = chapterId;
    }

    public String getLessonName() {
        return lessonName;
    }

    public void setLessonName(String lessonName) {
        this.lessonName = lessonName;
    }

    public Integer getLessonType() {
        return lessonType;
    }

    public void setLessonType(Integer lessonType) {
        this.lessonType = lessonType;
    }

    public Long getVideoId() {
        return videoId;
    }

    public void setVideoId(Long videoId) {
        this.videoId = videoId;
    }

    public Long getContentId() {
        return contentId;
    }

    public void setContentId(Long contentId) {
        this.contentId = contentId;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getIsFreePreview() {
        return isFreePreview;
    }

    public void setIsFreePreview(Integer isFreePreview) {
        this.isFreePreview = isFreePreview;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public boolean isVisible() {
        return status != null && status == STATUS_VISIBLE;
    }
}
