package com.edumatrix.vod.play.vo;

/**
 * 播放凭证响应（03-03 §8.1 字段生成规则）。
 *
 * @param vid              {@code vod_video.vod_file_id}，Aliplayer 以 {@code vid + playAuth} 起播
 * @param playAuth         阿里云 {@code GetVideoPlayAuth} 签发的凭证串。
 *                         <b>它有自己的有效期、由点播服务决定，不是 {@code authExpire}</b>
 * @param encryptType      固定 1 = Aliplayer 侧「私有加密」。
 *                         <b>与 {@code vod_video.encrypt_type = 2} 不是同一套编号，不要互相赋值</b>
 * @param sessionId        本次<b>播放</b>会话（不是本次取证）。换发凭证时保持不变
 * @param authToken        心跳用的身份票，请求头 {@code X-Play-Token} 携带。<b>与解密无关</b>
 * @param authExpire       {@code authToken} 的有效秒数，固定 300
 * @param maxPosition      最远触达位置（秒）。<b>F-113 后不再是「允许拖拽上限」</b>，
 *                         而是识别「看到多深」的数据
 * @param watchedDuration  累计观看秒数快照
 * @param watchStatus      0 未开始 / 1 学习中（<b>第一版不会出现 2</b>，完播判定延后）
 */
public record PlayAuthVO(String vid, String playAuth, int encryptType, String sessionId,
                         String authToken, int authExpire, int maxPosition,
                         int watchedDuration, int watchStatus) {
}
