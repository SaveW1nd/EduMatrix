package com.edumatrix.vod.media.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code vod_video} 云端媒资表（03-03 §7、02-数据库设计 §4.3.1）。
 *
 * <h2>状态机（契约 §5 {@code video_status}，03-03 §9 状态机速查）</h2>
 * <pre>
 * 0 上传中 ──FileUploadComplete──▶ 1 转码中 ──TranscodeComplete(success)──▶ 2 正常
 *                                     └──────TranscodeComplete(fail)─────▶ 3 转码失败
 * 3 ──（接口 33 重新发起转码 / 接口 25 重传源文件）──▶ 1        ← 【不经过 0】
 * 2 ◀──────────── 接口 34 人工禁用/启用 ────────────▶ 9
 * </pre>
 * <p><b>仅 {@code 2} 可挂课时、可发放播放凭证。</b>
 *
 * <h2>{@code vod_file_id} 在<b>发上传凭证时</b>就写入，不是等事件回填</h2>
 * <p>DDL 列注释逐字：「阿里云路径下发上传凭证时即写入（响应的 {@code cloudVideoId}），故非空；
 * NULL 仅出现在腾讯路径的预创建态」。这一条是契约 §2.8 规则 1 的前提 ——
 * 事件消费按 {@code vod_file_id} 反查租户，<b>反查链路自建号起就是闭合的</b>，
 * 不存在「事件先于写入到达」的竞态。
 *
 * <h2>{@code encrypt_type} 默认值与两次定案</h2>
 * <p>DDL 的默认值是 {@code 1}（HLS 标准加密）。R1a 定案改为 <b>{@code 2} 阿里云私有加密</b>，
 * <b>F-114（2026-08-21 需方定案）又改为「第一版不加密」→ 建行时显式写 {@code 0}</b>。
 * 三个值各出现过一次，正是<b>不能依赖 DDL 默认值</b>的理由 ——
 * 基线是冻结内容（契约 §7.3）改不了，而依赖一个与事实不符的默认值，
 * 迟早有人绕过 Service 直接 INSERT 出一行 {@code encrypt_type=1} 的媒资。
 *
 * <p><b>⚠ 本列必须与转码模板实际产出的东西一致</b>，它是事实记录不是意图声明：
 * 库里写 2 而模板产出明文流，播放侧会按私有加密去解密 —— 表现是「播不了」。
 * 模块 12 的 {@code play-auth} 正是按本列决定下发给 Aliplayer 的 {@code encryptType}。
 */
@TableName("vod_video")
public class VodVideo extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** {@code 0} 上传中。 */
    public static final int STATUS_UPLOADING = 0;
    /** {@code 1} 转码中。 */
    public static final int STATUS_TRANSCODING = 1;
    /** {@code 2} 正常 —— <b>仅此状态可挂课时、可发放播放凭证</b>。 */
    public static final int STATUS_NORMAL = 2;
    /** {@code 3} 转码失败。 */
    public static final int STATUS_FAILED = 3;
    /** {@code 9} 禁用（人工，接口 34）。 */
    public static final int STATUS_DISABLED = 9;

    /** 云厂商：{@code 1} 腾讯 {@code 2} 阿里。契约 §1：<b>阿里云是本期唯一实现</b>。 */
    public static final int PROVIDER_ALIYUN = 2;

    /** 加密方式：{@code 2} 阿里云私有加密（R1a 定案）。见类注释。 */
    public static final int ENCRYPT_ALIYUN_PRIVATE = 2;

    /**
     * {@code 0} 不加密 —— <b>F-114 定案：第一版按此写入</b>。
     *
     * <p>本列必须与<b>转码模板实际产出的东西</b>一致。写 2 而模板产出的是明文流，
     * 播放侧会按私有加密去解密，表现是「播不了」；反过来同样。
     * <b>这一列不是意图声明，是事实记录。</b>
     */
    public static final int ENCRYPT_NONE = 0;

    /** 归属节点。创建时写入上传者所在节点，<b>请求体不接受该参数</b>（03-03 §7.3 说明）。 */
    private Long ownerNodeId;

    private Integer provider;

    private Integer encryptType;

    /** 解密密钥 URI。私有加密下密钥由点播服务托管，本列在本期<b>不写</b>。 */
    private String decryptKeyUri;

    /** 云端媒资唯一 ID（阿里 {@code VideoId}）。事件消费按它反查租户。 */
    private String vodFileId;

    private String videoName;

    /** 时长（秒）。{@code TranscodeComplete(success)} 事件消费时回填。 */
    private Integer duration;

    private String coverUrl;

    private Long sizeBytes;

    private Integer status;

    /** 加密 HLS 播放地址。<b>列表接口不返回它</b>（03-03 §7.3 说明）。 */
    private String hlsUrl;

    private Long uploadUserId;

    public Long getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(Long ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

    public Integer getProvider() {
        return provider;
    }

    public void setProvider(Integer provider) {
        this.provider = provider;
    }

    public Integer getEncryptType() {
        return encryptType;
    }

    public void setEncryptType(Integer encryptType) {
        this.encryptType = encryptType;
    }

    public String getDecryptKeyUri() {
        return decryptKeyUri;
    }

    public void setDecryptKeyUri(String decryptKeyUri) {
        this.decryptKeyUri = decryptKeyUri;
    }

    public String getVodFileId() {
        return vodFileId;
    }

    public void setVodFileId(String vodFileId) {
        this.vodFileId = vodFileId;
    }

    public String getVideoName() {
        return videoName;
    }

    public void setVideoName(String videoName) {
        this.videoName = videoName;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getHlsUrl() {
        return hlsUrl;
    }

    public void setHlsUrl(String hlsUrl) {
        this.hlsUrl = hlsUrl;
    }

    public Long getUploadUserId() {
        return uploadUserId;
    }

    public void setUploadUserId(Long uploadUserId) {
        this.uploadUserId = uploadUserId;
    }
}
