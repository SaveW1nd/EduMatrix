package com.edumatrix.vod.media.vo;

/**
 * 接口 33 重新发起转码 与 接口 34 禁用/启用 的响应（03-03 §7.5 / §7.6）。
 *
 * <p>{@code refLessonCount} 只有 §7.6 返回（供前端在禁用确认弹窗提示影响面）；
 * §7.5 的响应只有 {@code videoId} 与 {@code status}，故该字段为 null 时不下发
 * ——{@code JacksonConfig} 的全局 {@code default-property-inclusion: always} 会把它输出成
 * {@code null}，这是本项目既有口径，前端按 null 判断即可。
 */
public class VideoStatusVO {

    private Long videoId;
    private Integer status;
    private Integer refLessonCount;

    public static VideoStatusVO of(Long videoId, Integer status) {
        VideoStatusVO vo = new VideoStatusVO();
        vo.videoId = videoId;
        vo.status = status;
        return vo;
    }

    public Long getVideoId() {
        return videoId;
    }

    public void setVideoId(Long videoId) {
        this.videoId = videoId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getRefLessonCount() {
        return refLessonCount;
    }

    public void setRefLessonCount(Integer refLessonCount) {
        this.refLessonCount = refLessonCount;
    }
}
