package com.edumatrix.course.catalog.vo;

import java.time.LocalDateTime;

/** 接口 12 课时分页列表的行（03-03 §3.1）。 */
public class LessonListVO {

    private Long id;

    private Long courseId;

    private Long chapterId;

    private String chapterName;

    private String lessonName;

    /** 1 视频 2 图文。 */
    private Integer lessonType;

    private Long videoId;

    /** {@code vod_video.status}：0 上传中 1 转码中 2 正常 3 转码失败 9 禁用。图文课时为 null。 */
    private Integer videoStatus;

    /** 落库列是 {@code crs_lesson.content_id}（03-03 §0.4 字段映射）。 */
    private Long materialId;

    /** 秒。图文恒 0。 */
    private Integer duration;

    private Integer sort;

    private Integer isFreePreview;

    /** 0 隐藏 1 可见。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getChapterName() {
        return chapterName;
    }

    public void setChapterName(String chapterName) {
        this.chapterName = chapterName;
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

    public Integer getVideoStatus() {
        return videoStatus;
    }

    public void setVideoStatus(Integer videoStatus) {
        this.videoStatus = videoStatus;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
