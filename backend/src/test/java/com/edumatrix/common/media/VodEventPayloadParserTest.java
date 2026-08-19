package com.edumatrix.common.media;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报文解析 —— <b>唯一一处拿真实生产报文钉住的地方</b>。
 *
 * <h2>为什么这一条比其他任何一条 IT 都重要</h2>
 * <p>本模块最可能的生产事故不是逻辑写错，而是<b>报文的字段形状与解析器对不上</b>：
 * 那样全部 IT 照样绿（假队列喂的是我们自己造的形状），
 * 而生产上<b>每一条消息都走解析失败分支</b>，媒资全部卡在 status=0。
 * 唯一能防住它的就是「拿真报文来测」。
 *
 * <p>下面 {@link #REAL_FILE_UPLOAD_COMPLETE} 是需方<b>从生产队列取回</b>的原始报文，
 * <b>一个字符都没有改</b>（只把过长的 FileUrl 保留原样）。
 *
 * <p><b>⚠ {@code TranscodeComplete} 的真实报文尚未拿到。</b>
 * 它才带转码产物信息，形状可能不同（比如带 {@code StreamInfos}）。
 * <b>本类刻意不为它编一条假的</b> —— 编出来只会给人「这条链路测过了」的错觉。
 * 拿到之后请在这里补一条同形状的用例，并把 {@code VodEventConsumeService} 的分支跟着核一遍。
 * 在那之前，那一半由「解析失败走孤儿处置（有指标、有日志）」兜住。
 */
class VodEventPayloadParserTest {

    /**
     * 生产队列里真实的一条 {@code FileUploadComplete}（需方 2026-08-19 取回）。
     *
     * <p>注意 {@code FileUrl} 里的 {@code .mp4} 是<b>上传的源文件</b>，不是转码产物 ——
     * 不要据此推断输出格式（契约 §1 的输出是单档加密 HLS）。
     */
    private static final String REAL_FILE_UPLOAD_COMPLETE = """
            {"Status":"success",\
            "FileUrl":"http://outin-14907bbf9bcd11f1b6eb0a75f10cffd7.oss-cn-shanghai.aliyuncs.com\
            /customerTrans/b1ad728a0b8a9702748b3c36aa922dcd/6169c89c-7202a.mp4",\
            "VideoId":"10f6baa29bdf71f1bfc46733a78e0102",\
            "EventType":"FileUploadComplete",\
            "EventTime":"2026-08-19T15:07:07Z",\
            "Size":4372373}""";

    @Test
    @DisplayName("真实 FileUploadComplete 报文：六个字段逐个解出来")
    void parsesTheRealFileUploadCompletePayload() {
        VodEvent event = VodEventPayloadParser.parse("receipt-1", REAL_FILE_UPLOAD_COMPLETE);

        assertThat(event.parsed())
                .as("解析失败 —— 生产上这意味着每一条消息都走孤儿处置，媒资全部卡在 status=0")
                .isTrue();
        assertThat(event.eventType()).isEqualTo("FileUploadComplete");
        assertThat(event.isUploadComplete()).isTrue();
        assertThat(event.isHandledType()).isTrue();
        // 报文里的 VideoId 就是 DDL 的 vod_video.vod_file_id
        //（列注释逐字：「云端媒资唯一ID（阿里 VideoId / 腾讯 FileId）」）
        assertThat(event.vodFileId()).isEqualTo("10f6baa29bdf71f1bfc46733a78e0102");
        assertThat(event.sizeBytes()).isEqualTo(4372373L);
        assertThat(event.receiptHandle()).isEqualTo("receipt-1");
    }

    /**
     * <b>FileUploadComplete 也带 {@code Status}</b>，不只 {@code TranscodeComplete} 带。
     *
     * <p>这条单独钉住，是因为幂等三段键 {@code vod_file_id + EventType + Status}
     * 的前置状态集分支必须覆盖到它 —— 契约 §2.8 讲「加 Status」的理由时只举了
     * {@code TranscodeComplete}，容易让人以为另一类没有这个字段。
     */
    @Test
    @DisplayName("FileUploadComplete 同样带 Status=success（幂等三段键的分支要覆盖到）")
    void uploadCompleteAlsoCarriesStatus() {
        VodEvent event = VodEventPayloadParser.parse("receipt-1", REAL_FILE_UPLOAD_COMPLETE);

        assertThat(event.status()).isEqualTo("success");
        assertThat(event.isTranscodeSuccess())
                .as("它是上传完成不是转码完成 —— 判定必须同时看 EventType，"
                        + "只看 Status 会把上传完成当成转码成功，直接置 status=2 且 hls_url 为空")
                .isFalse();
        assertThat(event.isTranscodeFailure()).isFalse();
    }

    /**
     * <b>EventTime 是 UTC，必须转成东八区。</b>
     *
     * <p>报文里是 {@code 2026-08-19T15:07:07Z}，实际上传时刻是东八区 {@code 23:07:07}，
     * <b>差 8 小时</b>。不转换的话「不报错、字段齐全、值也像时间」，只是全部早 8 小时 ——
     * 而契约 §6.1 要求服务器、数据库、接口三层统一 {@code Asia/Shanghai}，
     * 且 {@code vod_heartbeat_log} 的月分区边界与 {@code stat_*} 的自然日结算
     * 都建立在「只有一个时区」上，这里错了会往下游渗。
     *
     * <p>变异：把 {@code Instant.parse} 换成把 {@code Z} 去掉后 {@code LocalDateTime.parse}
     * （即「把 UTC 时刻直接当本地时刻」）→ 本条红，得到 15:07:07。
     */
    @Test
    @DisplayName("EventTime 按 UTC 解析后转东八区：15:07:07Z → 23:07:07（差 8 小时）")
    void eventTimeIsConvertedFromUtcToShanghai() {
        VodEvent event = VodEventPayloadParser.parse("receipt-1", REAL_FILE_UPLOAD_COMPLETE);

        assertThat(event.eventTime())
                .as("EventTime 没有按 UTC 解析再转东八区 —— 全部事件时间会早 8 小时，"
                        + "而且不报错（契约 §6.1：三层统一 Asia/Shanghai）")
                .isEqualTo(LocalDateTime.of(2026, 8, 19, 23, 7, 7));
    }

    /**
     * 队列的消息体<b>是否 Base64 取决于队列的编码设置</b>，猜错一边的后果是每条消息都解析失败。
     * 所以两种都吃，两种都测。
     */
    @Test
    @DisplayName("消息体是 Base64 时同样解得出（队列编码设置决定，猜错则每条都失败）")
    void parsesBase64EncodedBody() {
        String base64 = Base64.getEncoder().encodeToString(
                REAL_FILE_UPLOAD_COMPLETE.getBytes(StandardCharsets.UTF_8));

        VodEvent event = VodEventPayloadParser.parse("receipt-1", base64);

        assertThat(event.parsed()).isTrue();
        assertThat(event.vodFileId()).isEqualTo("10f6baa29bdf71f1bfc46733a78e0102");
        assertThat(event.eventTime()).isEqualTo(LocalDateTime.of(2026, 8, 19, 23, 7, 7));
    }

    /**
     * 解析失败<b>不抛异常</b>：抛了会让整轮消费中断，而队列里可能只有那一条是坏的。
     * 返回一个 {@code parsed()=false} 的对象，由调用方走孤儿处置。
     */
    @Test
    @DisplayName("坏报文不抛异常，返回 parsed()=false 并保住 rawBody（孤儿处置要靠它说清收到了什么）")
    void badPayloadIsReportedNotThrown() {
        for (String bad : new String[]{"", "   ", "not json at all", "{\"EventType\":\"X\"}",
                "{\"VideoId\":\"v1\"}", "[1,2,3]"}) {
            VodEvent event = VodEventPayloadParser.parse("receipt-x", bad);
            assertThat(event.parsed())
                    .as("这条应当被判为解析失败：%s", bad)
                    .isFalse();
            assertThat(event.receiptHandle())
                    .as("句柄必须留住 —— 孤儿消息也要删掉，不删会被无限重投（契约 §2.8 规则 3）")
                    .isEqualTo("receipt-x");
        }
    }

    /**
     * {@code StreamTranscodeComplete} 解得出来，但<b>不是本模块处理的类型</b>。
     *
     * <p>它是<b>单个清晰度</b>完成，按它跃迁状态会「低清先转完就置 2、高清地址永远写不进去」，
     * 且源视频损坏时收不到任何失败通知（03-03 §7.2）。
     */
    @Test
    @DisplayName("StreamTranscodeComplete 解得出但不是可处理类型（不得用于状态跃迁）")
    void streamTranscodeCompleteIsParsedButNotHandled() {
        VodEvent event = VodEventPayloadParser.parse("receipt-2",
                "{\"EventType\":\"StreamTranscodeComplete\",\"Status\":\"success\","
                        + "\"VideoId\":\"10f6baa29bdf71f1bfc46733a78e0102\"}");

        assertThat(event.parsed()).isTrue();
        assertThat(event.isHandledType())
                .as("按 StreamTranscodeComplete 跃迁状态 = 低清先转完就置 2、高清地址永远写不进去")
                .isFalse();
    }
}
