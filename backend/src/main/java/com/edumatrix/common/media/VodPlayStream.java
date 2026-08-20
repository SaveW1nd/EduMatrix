package com.edumatrix.common.media;

/**
 * {@code GetPlayInfo} 返回的<b>一路</b>播放流（03-03 §7.2、契约 §1 部署约定第 2 条）。
 *
 * <p>字段与类型<b>逐个对着 SDK 的真实签名</b>取（{@code aliyun-java-sdk-vod} 2.16.34 的
 * {@code GetPlayInfoResponse$PlayInfo}，反编译 jar 确认）：
 * <table border="1">
 *   <caption>别按直觉猜类型</caption>
 *   <tr><th>SDK 方法</th><th>返回类型</th><th>直觉会猜成</th></tr>
 *   <tr><td>{@code getFormat()}</td><td>{@code String}</td><td>—</td></tr>
 *   <tr><td>{@code getEncrypt()}</td><td><b>{@code Long}</b></td><td>{@code boolean}</td></tr>
 *   <tr><td>{@code getEncryptType()}</td><td>{@code String}</td><td>—</td></tr>
 *   <tr><td>{@code getEncryptMode()}</td><td>{@code String}</td><td>—</td></tr>
 *   <tr><td>{@code getDuration()}</td><td><b>{@code String}</b></td><td>{@code Float} / 浮点秒</td></tr>
 *   <tr><td>{@code getSize()}</td><td>{@code Long}</td><td>—</td></tr>
 * </table>
 *
 * @param format      {@code m3u8} / {@code mp4} / …
 * @param encrypt     {@code 1} = 已加密。<b>不是布尔</b>
 * @param encryptType 加密类型，如 {@code AliyunVoDEncryption}（私有加密）/ {@code HLSEncryption}（标准加密）
 * @param encryptMode 加密模式。<b>本模块不读它</b>，见 {@link #isEncryptedHls()}
 * @param playUrl     播放地址。<b>必须是 https</b>，否则学生端会被混合内容拦截
 * @param duration    时长，<b>字符串形式的浮点秒</b>
 * @param sizeBytes   该路产物的字节数
 * @param definition  清晰度标识（{@code LD}/{@code SD}/{@code HD}…），仅用于日志诊断
 */
public record VodPlayStream(String format,
                            Long encrypt,
                            String encryptType,
                            String encryptMode,
                            String playUrl,
                            String duration,
                            Long sizeBytes,
                            String definition) {

    /** 契约 §1 部署约定第 2 条写死的输出格式。 */
    public static final String FORMAT_M3U8 = "m3u8";

    /** {@code getEncrypt()} 的「已加密」取值。SDK 给的是 {@code Long}，不是布尔。 */
    public static final long ENCRYPTED = 1L;

    /**
     * 契约 §1 部署约定第 2 条：<b>挑选规则写死为 {@code Format == "m3u8" && Encrypt == true}</b>。
     *
     * <h2>三个加密字段并存，这里<b>只读 {@code Encrypt}</b>，另外两个刻意不读</h2>
     * <ul>
     *   <li><b>{@code EncryptType}（如 {@code AliyunVoDEncryption}）不读</b>：读了就等于把
     *       「用哪种加密」硬编码进挑流条件，而那是<b>模板组的配置</b>、需方可改
     *       （R1a 定案已从 HLS 标准加密改为阿里云私有加密一次）。挑流要回答的是
     *       「这一路能不能给学生播」，不是「它用的哪种加密」。<b>加密方式变更不应让挑流失灵。</b></li>
     *   <li><b>{@code EncryptMode} 不读</b>：名字最像「加密方式」，所以最容易被下一个人误取。
     *       它描述的是加密的<b>模式</b>参数，与「这一路是不是加密流」不是同一个问题。</li>
     * </ul>
     * <p><b>而 {@code Encrypt} 单独也不够</b>，所以必须与 {@code Format} 联合判 ——
     * 契约 §1 第 2 条逐字：「DRM 与私有加密下 {@code Encrypt} 同样为 {@code true}；
     * 而模板组若被误配成输出未加密 MP4，只按 {@code Encrypt} 挑会得到<b>空集</b>」。
     */
    public boolean isEncryptedHls() {
        return FORMAT_M3U8.equalsIgnoreCase(format) && Long.valueOf(ENCRYPTED).equals(encrypt);
    }

    /**
     * 播放地址必须是 https。
     *
     * <p>契约「报文里的 URL 一律不采信」的整个论证建立在「全站 HTTPS，http 地址会被浏览器
     * 按混合内容拦截」之上；反调 {@code GetPlayInfo} 换来的地址<b>同样要过这一关</b>，
     * 否则只是把同一个坑挪了个位置。<b>不做 http→https 改写</b>：改写是猜测（未必可达），
     * 而存一条播不了的地址是「看起来成功了」的失败。
     */
    public boolean isHttps() {
        return playUrl != null && playUrl.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * 时长向上取整成秒（03-03 §7.2：「{@code duration} 取该路 {@code Duration}（浮点秒，{@code ceil} 取整）」）。
     *
     * @return 解析不出时返回 {@code null} —— 调用方<b>必须</b>据此走失败分支，
     *         <b>绝不可当成 0</b>：那会写出一条 {@code duration=0} 的「正常」媒资，
     *         课时能上架、进度条算不出完播，且不报错
     */
    public Integer durationSeconds() {
        if (duration == null || duration.isBlank()) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(duration.trim());
            if (seconds < 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
                return null;
            }
            return (int) Math.ceil(seconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 日志诊断用：挑流失败时要说清「云端到底返回了什么」。 */
    public String describe() {
        return "format=" + format + " encrypt=" + encrypt + " encryptType=" + encryptType
                + " definition=" + definition + " https=" + isHttps();
    }
}
