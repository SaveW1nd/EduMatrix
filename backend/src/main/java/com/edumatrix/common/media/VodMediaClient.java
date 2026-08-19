package com.edumatrix.common.media;

/**
 * 点播服务的窄视图（上传凭证 / 播放信息 / 提交转码），03-03 §7.1 / §7.2 / §7.5。
 *
 * <p>接口在 {@code common/}、实现在 {@code integration/aliyun/VodClient}，
 * 与 {@code common/file/ObjectStorage} ← {@code OssClient} 同型
 * （05-工程结构.md §G2：{@code ALIYUN_*} / {@code VOD_*} 的消费方只有 {@code integration/aliyun/**}）。
 *
 * <p>抽这一层<b>不是</b>为了将来换云厂商（契约 §1：阿里云是本期唯一实现），
 * 而是为了让上传/转码/挑流的判定能在<b>没有真云账号</b>的情况下被测到 ——
 * 生产机 {@code /etc/edumatrix/db.env} 里连 {@code ALIYUN_VOD_*} 都还没有。
 */
public interface VodMediaClient {

    /**
     * 新建一次上传（{@code CreateUploadVideo}）。
     *
     * <p><b>调用方必须先调它、再落库</b>：DDL 对 {@code vod_file_id} 的注释逐字
     * 「阿里云路径下发上传凭证时即写入……NULL 仅出现在腾讯路径的预创建态」——
     * 先落库会造出一条阿里云路径下不该存在的 NULL 行。
     */
    VodUploadCredential createUploadVideo(String title, String fileName, long fileSize);

    /** 续签 / 重传（{@code RefreshUploadVideo}），媒资已存在，只换凭证。 */
    VodUploadCredential refreshUploadVideo(String cloudVideoId);

    /**
     * 取播放信息（{@code GetPlayInfo}）。
     *
     * <p>契约 §2.8：<b>报文里的 URL 一律不采信</b>（官方返回 http，全站 HTTPS 下会被
     * 混合内容拦截），播放地址一律反调本方法取。
     */
    VodPlayInfo getPlayInfo(String cloudVideoId);

    /** 重新发起转码（{@code SubmitTranscodeJobs}），用配置里的模板组。 */
    void submitTranscodeJobs(String cloudVideoId);

    /** 本实现是不是「未配置」的空实现。 */
    default boolean enabled() {
        return true;
    }
}
