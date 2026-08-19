package com.edumatrix.common.media;

/**
 * 从轻量消息队列取到的一条<b>原始</b>转码事件（03-03 §7.2）。
 *
 * <p><b>刻意保留 {@code rawBody}</b>：解析失败时它是唯一能说清「云端到底发来了什么」的东西，
 * 而「报文字段形状与我们的解析器对不上」是本模块<b>最可能的生产事故</b> ——
 * 假实现全绿而生产上每条消息都解析不出来。日志里只截前若干字节，不整条打。
 *
 * @param receiptHandle 删除消息用的句柄。<b>落库成功后才拿它去删</b>（契约 §2.8：顺序反了就是丢事件）
 * @param eventType     {@code FileUploadComplete} / {@code TranscodeComplete} / 其他（含
 *                      {@code StreamTranscodeComplete}，<b>不得用于状态跃迁</b>）；解析不出时 {@code null}
 * @param status        {@code TranscodeComplete} 才有：{@code success} / {@code fail}；其余为 {@code null}
 * @param vodFileId     云端媒资 ID（阿里云 {@code VideoId}）→ {@code vod_video.vod_file_id}；解析不出时 {@code null}
 * @param errorCode     失败事件的错误码，写入 {@code remark}
 * @param errorMessage  失败事件的错误描述，写入 {@code remark}
 * @param rawBody       原始报文
 */
public record VodEvent(String receiptHandle,
                       String eventType,
                       String status,
                       String vodFileId,
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

    /** {@code TranscodeComplete} 的成功取值。 */
    public static final String STATUS_SUCCESS = "success";

    /** 报文可用（有 {@code eventType} 且有 {@code vodFileId}）。缺任一项都走孤儿处置。 */
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
}
