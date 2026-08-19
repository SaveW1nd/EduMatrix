package com.edumatrix.common.media;

/**
 * {@code vod_video} 的三列窄视图 —— 模块 08 校验课时关联时需要的<b>全部</b>信息。
 *
 * <p>刻意不做成 {@code VodVideo} 实体：模块 09 会建真正的实体，
 * 这里多存一列都是将来两处要同步维护的负担。
 *
 * @param id       媒资 ID
 * @param status   {@code 0} 上传中 {@code 1} 转码中 {@code 2} 正常 {@code 3} 转码失败
 *                 {@code 9} 禁用（03-03 §7 导语 / 附「状态机速查」/ 契约 §4）
 * @param duration 时长（秒）。视频课时的 {@code crs_lesson.duration} 冗余自它
 * @param videoName 媒资名，用于 03-03 §3.2 的 {@code videoName} 与 §1.6 的失败文案
 */
public record VideoRef(Long id, Integer status, Integer duration, String videoName) {

    /** {@code vod_video.status = 2 正常} —— 附「状态机速查」逐字：<b>仅 2 可挂课时、可发放播放凭证</b>。 */
    public static final int STATUS_NORMAL = 2;

    public boolean isNormal() {
        return status != null && status == STATUS_NORMAL;
    }
}
