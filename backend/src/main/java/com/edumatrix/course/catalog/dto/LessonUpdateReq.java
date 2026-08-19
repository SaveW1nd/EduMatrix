package com.edumatrix.course.catalog.dto;

/**
 * 接口 15 修改课时（03-03 §3.4）。
 *
 * <p>允许变更 {@code lessonType}，但必须同时传入新类型对应的资源 ID（否则 {@code 20019}）。
 * {@code chapterId} 可传入以移动课时到<b>同一课程内</b>的其他章节；跨课程移动被拒
 * （{@code 20007}）—— {@code course_id} 是冗余列，跨课程移动会让两个课程的
 * {@code lesson_count} 同时失真。
 */
public class LessonUpdateReq {

    /** 移动到的章节 ID（<b>同课程内</b>）。不传表示不移动。 */
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
