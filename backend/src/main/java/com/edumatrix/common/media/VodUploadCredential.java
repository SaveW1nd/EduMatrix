package com.edumatrix.common.media;

/**
 * 一次性上传凭证（03-03 §7.1）。前端拿它用阿里云 {@code AliyunUpload} SDK <b>直传</b>，
 * 视频流量不过应用服务器。
 *
 * @param cloudVideoId 阿里云 {@code VideoId}。<b>发凭证时即写入 {@code vod_video.vod_file_id}</b> ——
 *                     事件反查链路自建号起就是闭合的（契约 §2.8 规则 1：不存在「事件先于写入到达」的竞态）
 * @param uploadAuth    上传凭证
 * @param uploadAddress 上传地址
 */
public record VodUploadCredential(String cloudVideoId, String uploadAuth, String uploadAddress) {
}
