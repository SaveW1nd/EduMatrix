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

    /** Aliplayer 侧 {@code encryptType}：1 = 私有加密。 */
    public static final int ALIPLAYER_ENCRYPT_TYPE_PRIVATE = 1;

    /** Aliplayer 侧 {@code encryptType}：0 = 不加密（普通视频，播放器不走解密通道）。 */
    public static final int ALIPLAYER_ENCRYPT_TYPE_NONE = 0;

    /**
     * 把库里的 {@code vod_video.encrypt_type} 翻译成下发给 Aliplayer 的 {@code encryptType}。
     *
     * <p><b>⚠ 两边都叫 encryptType、都是小整数、都在描述同一件事，但【不是同一套编号】</b>
     * （F-112 已点名这是必然会有人踩的地方）：
     *
     * <table>
     *   <tr><th>{@code vod_video.encrypt_type}</th><th>下发给 Aliplayer</th></tr>
     *   <tr><td>0 不加密（<b>F-114：第一版</b>）</td><td>0</td></tr>
     *   <tr><td>1 HLS 标准加密</td><td>0 —— Aliplayer 的这个参数<b>只表示私有加密</b>，
     *       标准加密由播放器按 m3u8 里的 {@code #EXT-X-KEY} 自己处理</td></tr>
     *   <tr><td>2 阿里云私有加密</td><td>1</td></tr>
     * </table>
     *
     * <p><b>做成映射而不是写死常量</b>：第一版不加密、以后可能打开，
     * 而<b>存量视频与新视频会长期混在一起</b>（早先上传的是 encrypt_type=2）。
     * 按行翻译使两者都能播；写死任何一个值都会让另一半播不了，
     * 而那个表现是「视频打不开」，与代码 bug 不可区分。
     */
    public static int aliplayerEncryptTypeOf(Integer videoEncryptType) {
        return videoEncryptType != null && videoEncryptType == 2
                ? ALIPLAYER_ENCRYPT_TYPE_PRIVATE : ALIPLAYER_ENCRYPT_TYPE_NONE;
    }

    /** {@code vod_play_auth_log.event_type}：1 播放凭证。取值 2 随接口 29 删除后不再写入（F-112）。 */
    public static final int EVENT_TYPE_PLAY_AUTH = 1;

    /** {@code sys_user.user_type}：3 学生。 */
    public static final int VIEWER_TYPE_STUDENT = 3;

    private PlayAuthConst() {
    }
}
