package com.edumatrix.course.catalog.dto;

/**
 * 接口 14 创建课时（03-03 §3.3）。
 *
 * <p><b>不接受 {@code duration}</b>：视频课时冗余自 {@code vod_video.duration}，
 * 图文课时恒 0（§3.3 规则 2 / 3）。
 * <b>不接受 {@code courseId}</b>：由服务端根据 {@code chapterId} 自动冗余写入（规则 1）。
 */
public class LessonCreateReq {

    /** 所属章节 ID（章或节，推荐节）。不属于目标课程 → {@code 20007}。 */
    @jakarta.validation.constraints.NotNull(message = "不能为空")
    private Long chapterId;

    public Long getChapterId() {
        return chapterId;
    }

    public void setChapterId(Long chapterId) {
        this.chapterId = chapterId;
    }

    @jakarta.validation.constraints.NotBlank(message = "不能为空")
    @jakarta.validation.constraints.Size(min = 1, max = 100, message = "长度须为 1~100 字符")
    private String lessonName;

    /** 1 视频 2 图文。 */
    @jakarta.validation.constraints.NotNull(message = "不能为空")
    @jakarta.validation.constraints.Min(value = 1, message = "只允许 1 视频 或 2 图文")
    @jakarta.validation.constraints.Max(value = 2, message = "只允许 1 视频 或 2 图文")
    private Integer lessonType;

    /** {@code lessonType=1} 时必填（缺失 → {@code 20019}），→ {@code vod_video.id}。 */
    private Long videoId;

    /**
     * {@code lessonType=2} 时必填（缺失 → {@code 20019}），→ {@code crs_material.id}。
     * 落库列是 {@code crs_lesson.content_id}（03-03 §0.4 的字段映射）。
     */
    private Long materialId;

    private Integer sort;

    /** 是否免费试看：0 否 1 是，默认 0。 */
    private Integer isFreePreview;

    /** 状态：0 隐藏 1 可见，默认 1。 */
    private Integer status;

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

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
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
}
