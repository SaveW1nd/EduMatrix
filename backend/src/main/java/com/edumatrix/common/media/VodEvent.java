package com.edumatrix.common.media;

import java.time.LocalDateTime;

/**
 * 从轻量消息队列取到的一条转码事件（03-03 §7.2）。
 *
 * <p><b>刻意保留 {@link #rawBody}</b>：解析失败时它是唯一能说清「云端到底发来了什么」的东西，
 * 而「报文字段形状与我们的解析器对不上」是本模块<b>最可能的生产事故</b> ——
 * 假实现全绿而生产上每条消息都解析不出来。日志里只截前若干字节，不整条打。
 *
 * <h2>字段名以<b>真实报文</b>为准（需方从生产队列取回的 {@code FileUploadComplete}）</h2>
 * <pre>
 * {"Status":"success",
 *  "FileUrl":"http://outin-….oss-cn-shanghai.aliyuncs.com/customerTrans/…/….mp4",
 *  "VideoId":"10f6baa29bdf71f1bfc46733a78e0102",
 *  "EventType":"FileUploadComplete",
 *  "EventTime":"2026-08-19T15:07:07Z",
 *  "Size":4372373}
 * </pre>
 * 三条由这份报文坐实的事实：
 * <ol>
 *   <li><b>{@code FileUploadComplete} 也带 {@code Status}</b>，不只 {@code TranscodeComplete} 带 ——
 *       幂等三段键 {@code vod_file_id + EventType + Status} 照常成立，但前置状态集的分支要覆盖到；</li>
 *   <li><b>{@code FileUrl} 是 {@code http://}</b> —— 契约「报文里的 URL 一律不采信」被实测坐实。
 *       <b>注意这条报文里的 {@code .mp4} 是上传的<b>源文件</b>，不是转码产物</b>，
 *       不要据此推断输出格式；</li>
 *   <li><b>{@code EventTime} 是 UTC</b>（{@code …T15:07:07Z} = 东八区 23:07:07）——
 *       见 {@link #eventTime}。</li>
 * </ol>
 *
 * <p><b>⚠ {@code TranscodeComplete} 的真实报文尚未拿到</b>（需方拿到后补）。
 * 它才带转码产物信息，形状可能不同（比如带 {@code StreamInfos}）。
 * <b>在拿到之前不臆造它的字段形状</b> —— 由「解析失败走孤儿处置（有指标、有日志）」兜住，
 * 见 {@code VodEventConsumeService}。
 *
 * @param receiptHandle 删除消息用的句柄。<b>落库成功后才拿它去删</b>（契约 §2.8：顺序反了就是丢事件）
 * @param eventType     {@code FileUploadComplete} / {@code TranscodeComplete} / 其他（含
 *                      {@code StreamTranscodeComplete}，<b>不得用于状态跃迁</b>）；解析不出时 {@code null}
 * @param status        {@code success} / {@code fail}；解析不出时 {@code null}
 * @param vodFileId     报文里的 {@code VideoId}。<b>它就是 DDL 的 {@code vod_video.vod_file_id}</b>
 *                      （列注释逐字：「云端媒资唯一ID（阿里 VideoId / 腾讯 FileId）」）
 * @param eventTime     事件时间，<b>已由 UTC 转成东八区</b>。报文里是 {@code 2026-08-19T15:07:07Z}，
 *                      对应东八区 {@code 2026-08-19 23:07:07}，<b>差 8 小时</b>。
 *                      不转换的话「不报错、字段齐全、值也像时间」，只是全部早 8 小时 ——
 *                      而契约 §6.1 要求服务器、数据库、接口三层统一 Asia/Shanghai，
 *                      且 {@code vod_heartbeat_log} 的月分区边界与 {@code stat_*} 的自然日结算
 *                      都建立在「只有一个时区」上，这里错了会往下游渗。
 *                      解析不出时 {@code null}（不代表报文不可用，本字段不参与 {@link #parsed()}）
 * @param sizeBytes     报文里的 {@code Size}。{@code FileUploadComplete} 上是<b>源文件</b>大小；
 *                      <b>本模块不据此写 {@code vod_video.size_bytes}</b>，理由见 {@code VodEventConsumeService}
 * @param errorCode     失败事件的错误码，写入 {@code remark}
 * @param errorMessage  失败事件的错误描述，写入 {@code remark}
 * @param rawBody       原始报文（已 Base64 解码后的 JSON 文本）
 */
public record VodEvent(String receiptHandle,
                       String eventType,
                       String status,
                       String vodFileId,
                       LocalDateTime eventTime,
                       Long sizeBytes,
                       String errorCode,
                       String errorMessage,
                       String rawBody) {

    /** 上传完成。阿里云路径下 {@code vod_file_id} 在发凭证时即已写入，此处只推进状态。 */
    public static final String TYPE_FILE_UPLOAD_COMPLETE = "FileUploadComplete";

    /**
     * 转码完成。<b>成功与失败共用同一个 {@code EventType}</b> —— 这正是幂等判定必须带上
     * {@link #status} 的原因（契约 §2.8）。
     */
    public static final String TYPE_TRANSCODE_COMPLETE = "TranscodeComplete";

    /** {@code Status} 的成功取值。真实报文里是全小写的 {@code "success"}。 */
    public static final String STATUS_SUCCESS = "success";

    /** 报文可用（有 {@code EventType} 且有 {@code VideoId}）。缺任一项都走孤儿处置。 */
    public boolean parsed() {
        return eventType != null && !eventType.isBlank()
                && vodFileId != null && !vodFileId.isBlank();
    }

    /** 转码成功事件（{@code TranscodeComplete} + {@code Status=success}），大小写不敏感。 */
    public boolean isTranscodeSuccess() {
        return TYPE_TRANSCODE_COMPLETE.equalsIgnoreCase(eventType)
                && STATUS_SUCCESS.equalsIgnoreCase(status);
    }

    /** 转码失败事件（{@code TranscodeComplete} 且 {@code Status} 不是 success）。 */
    public boolean isTranscodeFailure() {
        return TYPE_TRANSCODE_COMPLETE.equalsIgnoreCase(eventType) && !isTranscodeSuccess();
    }

    /** 上传完成事件。 */
    public boolean isUploadComplete() {
        return TYPE_FILE_UPLOAD_COMPLETE.equalsIgnoreCase(eventType);
    }

    /** 本模块只处理这两类；其余（含 {@code StreamTranscodeComplete}）删掉并记 WARN。 */
    public boolean isHandledType() {
        return isUploadComplete() || TYPE_TRANSCODE_COMPLETE.equalsIgnoreCase(eventType);
    }

    /** 日志用的短摘要：<b>不打 {@link #rawBody} 全文</b>（可能很长，且含 OSS 直链）。 */
    public String describe() {
        return "eventType=" + eventType + " status=" + status + " videoId=" + vodFileId
                + " eventTime=" + eventTime;
    }
}
