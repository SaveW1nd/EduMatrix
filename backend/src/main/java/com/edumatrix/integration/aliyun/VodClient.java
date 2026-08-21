package com.edumatrix.integration.aliyun;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.vod.model.v20170321.CreateUploadVideoRequest;
import com.aliyuncs.vod.model.v20170321.CreateUploadVideoResponse;
import com.aliyuncs.vod.model.v20170321.GetPlayInfoRequest;
import com.aliyuncs.vod.model.v20170321.GetVideoPlayAuthRequest;
import com.aliyuncs.vod.model.v20170321.GetVideoPlayAuthResponse;
import com.aliyuncs.vod.model.v20170321.GetPlayInfoResponse;
import com.aliyuncs.vod.model.v20170321.RefreshUploadVideoRequest;
import com.aliyuncs.vod.model.v20170321.RefreshUploadVideoResponse;
import com.aliyuncs.vod.model.v20170321.SubmitTranscodeJobsRequest;
import com.edumatrix.common.media.VodMediaClient;
import com.edumatrix.common.media.VodNotReadyException;
import com.edumatrix.common.media.VodPlayInfo;
import com.edumatrix.common.media.VodPlayStream;
import com.edumatrix.common.media.VodUploadCredential;

/**
 * 阿里云点播（VOD）客户端 —— 上传凭证、播放信息、提交转码（03-03 §7.1 / §7.2 / §7.5）。
 *
 * <h2>{@code ALIYUN_*} 的消费方只有本包</h2>
 * <p>05-工程结构.md §G2：任何业务包里出现 {@code @Value("${ALIYUN_...}")} 都是越界。
 * AK/SK 与 {@code OssClient} 共用同一对（契约 §5：<b>全平台单一账号，不按机构分配</b>）。
 *
 * <h2>模板组 ID 在这里，不在业务代码里</h2>
 * <p>契约 §1 部署约定：模板组必须是<b>单一清晰度 + 加密</b>输出。
 * {@code CreateUploadVideo} 与 {@code SubmitTranscodeJobs} <b>两处都要带上它</b> ——
 * <b>漏带则走点播的默认模板组</b>，产出可能是未加密 MP4，而这件事只会在挑流为空时才暴露：
 * 那时看起来像「转码失败」，不像「配置漏了」。
 *
 * <h2>字段类型逐个对着 SDK 真实签名取，不按直觉猜</h2>
 * <p>{@code PlayInfo.getEncrypt()} 是 <b>{@code Long}</b> 不是 {@code boolean}；
 * {@code getDuration()} 是 <b>{@code String}</b> 不是浮点数（反编译
 * {@code aliyun-java-sdk-vod} 2.16.34 确认）。猜错的后果分别是
 * 「每个视频都挑不到流」与「时长恒为 0 的正常媒资」。
 * <b>判定与转换写在 {@link VodPlayStream}，本类只做搬运</b> —— 那样它们才测得到。
 *
 * <h2>硬超时</h2>
 * <p>连接 3s / 读 5s。没有它，「卡死」是无界的 —— 独立线程池只保证不传染，
 * 不保证它自己会好（见 {@code common/config/SchedulerConfig}）。
 */
@Component("vodClient")
@ConditionalOnExpression("'${edumatrix.vod.region:}'.trim() != ''"
        + " and '${edumatrix.vod.template-group-id:}'.trim() != ''")
public class VodClient implements VodMediaClient {

    private static final Logger log = LoggerFactory.getLogger(VodClient.class);

    private static final int CONNECT_TIMEOUT_MS = 3000;

    /**
     * 点播 API 的读超时。<b>与 {@code SmqClient} 拉消息的那个是两个值，不共用。</b>
     *
     * <p>两者性质相反：这里是<b>请求-响应</b>（{@code GetPlayInfo} 之类），5 秒不回就是真的慢；
     * 那边是<b>长轮询</b>，30 秒不回是<b>正常的</b>。共用一个值必然有一边是错的 ——
     * 生产上已经踩过一次：拉消息沿用了请求-响应的 5 秒，结果队列空时每轮都超时。
     * {@code SmqClientTimeoutTest#queuePollingAndVodApiTimeoutsAreSeparate} 钉住这件事。
     */
    static final int READ_TIMEOUT_MS = 5000;

    /**
     * 私有加密下 {@code GetPlayInfo} <b>不带它会直接返回 {@code Forbidden.AliyunVoDEncryption}</b>
     * （POC 实测，见 {@code poc/r1a-aliplayer/RESULT.md}）。那个报错本身即「确已加密」的证据，
     * 但我们要的是流清单，所以必须带上。
     */
    private static final String RESULT_TYPE_MULTIPLE = "Multiple";

    private final IAcsClient acsClient;
    private final String templateGroupId;
    private final String encryptedTemplateGroupId;

    public VodClient(@Value("${edumatrix.vod.region}") String region,
                     @Value("${edumatrix.vod.template-group-id}") String templateGroupId,
                     @Value("${edumatrix.vod.template-group-id-encrypted:}") String encryptedTemplateGroupId,
                     @Value("${edumatrix.file.oss.access-key-id}") String accessKeyId,
                     @Value("${edumatrix.file.oss.access-key-secret}") String accessKeySecret) {
        this.templateGroupId = templateGroupId.trim();
        this.encryptedTemplateGroupId = encryptedTemplateGroupId == null ? "" : encryptedTemplateGroupId.trim();
        this.acsClient = new DefaultAcsClient(
                DefaultProfile.getProfile(region.trim(), accessKeyId, accessKeySecret));
        // 与 OssClient 那行「对象存储 = …」同型的可 grep 事实。【不打印任何凭据】
        log.info("点播 = 阿里云 VOD region={} templateGroup={}（契约 §1：模板组须为单一清晰度加密输出）",
                region.trim(), this.templateGroupId);
    }

    /** 不加密模板组（默认）。 */
    @Override
    public String defaultTemplateGroupId() {
        return templateGroupId;
    }

    /** 加密模板组；<b>留空表示本部署不提供加密选项</b>。 */
    @Override
    public String encryptedTemplateGroupId() {
        return encryptedTemplateGroupId;
    }

    @Override
    public VodUploadCredential createUploadVideo(String title, String fileName, long fileSize,
                                                 String templateGroupId) {
        CreateUploadVideoRequest request = new CreateUploadVideoRequest();
        request.setTitle(title);
        request.setFileName(fileName);
        request.setFileSize(fileSize);
        request.setTemplateGroupId(templateGroupId);
        applyTimeouts(request);
        try {
            CreateUploadVideoResponse response = acsClient.getAcsResponse(request);
            return new VodUploadCredential(response.getVideoId(),
                    response.getUploadAuth(), response.getUploadAddress());
        } catch (Exception e) {
            throw new IllegalStateException("申请上传凭证失败（CreateUploadVideo）" + describe(e), e);
        }
    }

    @Override
    public VodUploadCredential refreshUploadVideo(String cloudVideoId) {
        RefreshUploadVideoRequest request = new RefreshUploadVideoRequest();
        request.setVideoId(cloudVideoId);
        applyTimeouts(request);
        try {
            RefreshUploadVideoResponse response = acsClient.getAcsResponse(request);
            return new VodUploadCredential(response.getVideoId(),
                    response.getUploadAuth(), response.getUploadAddress());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "续签上传凭证失败（RefreshUploadVideo） videoId=" + cloudVideoId + describe(e), e);
        }
    }

    @Override
    public VodPlayInfo getPlayInfo(String cloudVideoId) {
        GetPlayInfoRequest request = new GetPlayInfoRequest();
        request.setVideoId(cloudVideoId);
        request.setResultType(RESULT_TYPE_MULTIPLE);
        applyTimeouts(request);
        try {
            GetPlayInfoResponse response = acsClient.getAcsResponse(request);
            List<VodPlayStream> streams = new ArrayList<>();
            if (response.getPlayInfoList() != null) {
                for (GetPlayInfoResponse.PlayInfo info : response.getPlayInfoList()) {
                    streams.add(new VodPlayStream(info.getFormat(), info.getEncrypt(),
                            info.getEncryptType(), info.getEncryptMode(), info.getPlayURL(),
                            info.getDuration(), info.getSize(), info.getDefinition()));
                }
            }
            String coverUrl = response.getVideoBase() == null
                    ? null : response.getVideoBase().getCoverURL();
            return new VodPlayInfo(streams, coverUrl);
        } catch (Exception e) {
            // 【未就绪】与【产物不对】必须分成两条路 —— 见 VodNotReadyException 类注释。
            // 混在一起会因为事件到达与云端状态翻转的时间差，把一条好视频永久标成转码失败
            if (isNotReady(e)) {
                throw new VodNotReadyException("点播侧尚未就绪（" + VodNotReadyException.ILLEGAL_STATUS
                        + "） videoId=" + cloudVideoId + describe(e));
            }
            throw new IllegalStateException(
                    "取播放信息失败（GetPlayInfo） videoId=" + cloudVideoId + describe(e), e);
        }
    }

    @Override
    public String getVideoPlayAuth(String cloudVideoId) {
        GetVideoPlayAuthRequest request = new GetVideoPlayAuthRequest();
        request.setVideoId(cloudVideoId);
        applyTimeouts(request);
        try {
            GetVideoPlayAuthResponse response = acsClient.getAcsResponse(request);
            String playAuth = response.getPlayAuth();
            if (playAuth == null || playAuth.isBlank()) {
                // 阿里云返回 200 但凭证为空 —— 当成失败而不是下发一个空串：
                // 空串到了播放器那儿表现是「播不了」，与「没取到凭证」不可区分
                throw new IllegalStateException(
                        "点播返回了空的播放凭证 videoId=" + cloudVideoId);
            }
            return playAuth;
        } catch (Exception e) {
            // 【不把 playAuth 写进日志】：它是能直接解密播放的凭证，落日志等于把它散出去
            throw new IllegalStateException(
                    "取播放凭证失败（GetVideoPlayAuth） videoId=" + cloudVideoId + describe(e), e);
        }
    }

    @Override
    public void submitTranscodeJobs(String cloudVideoId, String templateGroupId) {
        SubmitTranscodeJobsRequest request = new SubmitTranscodeJobsRequest();
        request.setVideoId(cloudVideoId);
        request.setTemplateGroupId(templateGroupId);
        applyTimeouts(request);
        try {
            acsClient.getAcsResponse(request);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "提交转码失败（SubmitTranscodeJobs） videoId=" + cloudVideoId + describe(e), e);
        }
    }

    private static void applyTimeouts(com.aliyuncs.RpcAcsRequest<?> request) {
        request.setSysConnectTimeout(CONNECT_TIMEOUT_MS);
        request.setSysReadTimeout(READ_TIMEOUT_MS);
    }

    /**
     * 是不是 {@code Forbidden.IllegalStatus}。
     *
     * <p><b>按 {@code ErrCode} 判，不按 message 文本判</b>：message 是
     * 「Currently Video Status is Transcoding and AuditStatus is Init」这种自然语言，
     * 阿里云随时可以改措辞，而错误码是契约面。
     */
    private static boolean isNotReady(Exception e) {
        try {
            Object code = e.getClass().getMethod("getErrCode").invoke(e);
            return VodNotReadyException.ILLEGAL_STATUS.equals(String.valueOf(code));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /** 只取异常类型与 {@code RequestId}，<b>不取 message 原文</b>（与 {@code OssClient} 同一条纪律）。 */
    private static String describe(Exception e) {
        String requestId = "";
        try {
            Object id = e.getClass().getMethod("getRequestId").invoke(e);
            requestId = id == null ? "" : String.valueOf(id);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 不是 ClientException / ServerException，没有 requestId —— 正常情况，不记
        }
        return " [" + e.getClass().getSimpleName() + " requestId=" + requestId + "]";
    }
}
