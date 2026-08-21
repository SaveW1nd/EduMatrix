package com.edumatrix.vod.play;

/**
 * 模块 12 常量。<b>模块 13 的心跳规则 1 直接读这里，不要另建一套</b>（04 §B 模块 12「对外产出」）。
 */
public final class PlayAuthConst {

    /**
     * 我们自己签发的 {@code authToken} 的 TTL（秒），固定 300（PRD F2-4 规则 1、03-03 §8.1）。
     *
     * <p><b>⚠ 这不是 playAuth 的有效期，两者不要混用同一个常量。</b>
     * {@code authToken} 是我们发的、只用于心跳的身份票，TTL 由我们定；
     * {@code playAuth} 是<b>阿里云点播服务签发</b>的播放凭证，<b>有它自己的有效期、我们控制不了</b>，
     * 也读不到（`GetVideoPlayAuth` 不回传剩余秒数）。03-03 §8.1.1 把这两种凭证列成了一张对照表，
     * 正是因为「两种凭证、两个有效期」最容易被写成一个。
     */
    public static final int AUTH_EXPIRE_SECONDS = 300;

    /**
     * 下发给 Aliplayer 的 {@code encryptType}，固定 1 =「私有加密」。
     *
     * <p><b>⚠ 它与 {@code vod_video.encrypt_type = 2}（本系统枚举「阿里云私有加密」）
     * 不是同一套编号，不要互相赋值。</b>两边都叫 encryptType、都是小整数、都描述同一件事，
     * 是必然会有人踩的地方（F-112 已点名）。
     */
    public static final int ALIPLAYER_ENCRYPT_TYPE_PRIVATE = 1;

    /** 水印随机刷新间隔下界（秒，含），03-03 §8.1、PRD F2-6 规则 2。 */
    public static final int WATERMARK_INTERVAL_MIN_SECONDS = 8;

    /** 水印随机刷新间隔上界（秒，含）。 */
    public static final int WATERMARK_INTERVAL_MAX_SECONDS = 15;

    /** {@code vod_play_auth_log.event_type}：1 播放凭证。取值 2 随接口 29 删除后不再写入（F-112）。 */
    public static final int EVENT_TYPE_PLAY_AUTH = 1;

    /** {@code sys_user.user_type}：3 学生。 */
    public static final int VIEWER_TYPE_STUDENT = 3;

    private PlayAuthConst() {
    }
}
