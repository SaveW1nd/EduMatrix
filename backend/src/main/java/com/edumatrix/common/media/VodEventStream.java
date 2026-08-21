package com.edumatrix.common.media;

/**
 * <b>事件报文里</b>的一路转码产物（{@code TranscodeComplete.StreamInfos[]}）。
 *
 * <h2>⚠ 它与 {@link VodPlayStream} 是<b>两个</b>类型，不许合并</h2>
 * <p>同一个概念在两处是<b>两种 JSON/Java 类型</b>（都由真实样本核实）：
 * <table border="1">
 *   <caption>别想着"统一一下"</caption>
 *   <tr><th>字段</th><th>{@code GetPlayInfo}（SDK）</th><th>事件报文（JSON）</th></tr>
 *   <tr><td>{@code Encrypt}</td><td>{@code java.lang.Long} = {@code 1}</td><td>布尔 {@code true}</td></tr>
 *   <tr><td>{@code Duration}</td><td>{@code java.lang.String}</td><td>数字 {@code 52.233433}</td></tr>
 *   <tr><td>{@code Bitrate}</td><td>—</td><td><b>字符串</b> {@code "1452"}（同一对象里！）</td></tr>
 * </table>
 * <p><b>阿里云自己都没统一</b>，所以合并成一个类型必然要靠隐式转换，
 * 而 Jackson 在某些配置下会把 {@code true} 悄悄读成数字、把 {@code "1"} 读成 {@code 1}，
 * <b>不报错</b>。这与模块 10 处理的判断题 {@code "true"} vs {@code true} 是同一形状：
 * 两者在 JSON 里都"看得过去"，一旦两端不一致就是全量判错且没有救济路径。
 *
 * <p>本记录的 {@code encrypt} / {@code durationSeconds} 由
 * {@link VodEventPayloadParser} <b>严格按 JSON 类型</b>取：类型不对就是 {@code null}，
 * <b>不做隐式转换</b>。{@code VodEventPayloadParserTest} 有一条专门喂
 * {@code GetPlayInfo} 那种形状进来，断言它<b>取不出值</b> ——
 * 悄悄兼容的那天，就是有人"统一一下"的那天。
 *
 * @param status          流自身的转码结果。<b>与顶层 {@code Status} 两层都要判</b>
 * @param format          {@code m3u8} / …
 * @param encrypt         <b>严格布尔</b>；报文里不是布尔时为 {@code null}
 * @param audio           {@code IsAudio} —— 存在纯音频流，挑流要排除
 * @param durationSeconds <b>严格数字</b>（浮点秒）；不是数字时为 {@code null}
 * @param sizeBytes       该路产物字节数
 * @param playUrl         {@code FileUrl}。实测仍是 {@code http://}，<b>一律不采信</b>（契约 §2.8）
 * @param definition      {@code SD}/{@code HD}…。<b>不能当画质依据</b> ——
 *                        实测 {@code Definition="SD"} 而实际 1280×720
 */
public record VodEventStream(String status,
                             String format,
                             Boolean encrypt,
                             Boolean audio,
                             Double durationSeconds,
                             Long sizeBytes,
                             String playUrl,
                             String definition) {

    /** 契约 §1 部署约定第 2 条写死的输出格式。 */
    public static final String FORMAT_M3U8 = "m3u8";

    /**
     * 契约 §1 第 2 条的联合判定，<b>事件形态版</b>：{@code Format == "m3u8" && Encrypt == true}，
     * 外加<b>排除纯音频流</b>（真实报文里有 {@code IsAudio} 字段）。
     *
     * <p>{@code encrypt} 是严格布尔 —— 报文里写成 {@code 1} 时这里是 {@code null}，
     * 判定为 false。<b>那是有意的</b>：宁可挑不到（响亮地置 3 并告警），
     * 也不要靠隐式转换蒙对一次、下次形状再变时悄悄蒙错。
     */
    public boolean isEncryptedHls() {
        return FORMAT_M3U8.equalsIgnoreCase(format)
                && Boolean.TRUE.equals(encrypt)
                && !Boolean.TRUE.equals(audio);
    }

    /**
     * 是不是（非纯音频的）m3u8 —— <b>加不加密都收</b>（F-114 第二半）。
     * 理由见 {@link VodPlayStream#isHls()}：挑流不问加密，落库时记实际值。
     *
     * <p><b>{@code audio} 那个排除保留</b>：纯音频轨不是要给学生播的那一路，与加密无关。
     */
    public boolean isHls() {
        return FORMAT_M3U8.equalsIgnoreCase(format) && !Boolean.TRUE.equals(audio);
    }

    /** 该路自身是否转码成功（顶层 {@code Status} 之外的第二层）。 */
    public boolean succeeded() {
        return VodEvent.STATUS_SUCCESS.equalsIgnoreCase(status);
    }

    /** 日志诊断：挑流失败时要说清云端到底返回了什么。 */
    public String describe() {
        return "format=" + format + " encrypt=" + encrypt + " audio=" + audio
                + " definition=" + definition + " status=" + status;
    }
}
