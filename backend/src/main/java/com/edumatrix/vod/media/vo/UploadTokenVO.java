package com.edumatrix.vod.media.vo;

/**
 * 接口 25 的响应（03-03 §7.1）。前端拿 {@link Credential} 用阿里云 {@code AliyunUpload} SDK
 * <b>直传</b>，视频流量不过应用服务器。
 */
public class UploadTokenVO {

    /** 本地媒资 ID（{@code vod_video.id}），不是云端的 VideoId。 */
    private Long videoId;
    /** 云厂商：1 腾讯 2 阿里。契约 §1：阿里云是本期唯一实现。 */
    private Integer provider;
    private Credential credential;

    /** 凭证三件套 + 过期时刻。 */
    public static class Credential {
        private String uploadAuth;
        private String uploadAddress;
        /** 阿里云 {@code VideoId}。<b>发凭证时即写入 {@code vod_file_id}</b>（契约 §2.8 规则 1）。 */
        private String cloudVideoId;
        /** 凭证有效期 3600s（§7.1 流程说明第 4 条）。 */
        private String expireTime;

        public String getUploadAuth() {
            return uploadAuth;
        }

        public void setUploadAuth(String uploadAuth) {
            this.uploadAuth = uploadAuth;
        }

        public String getUploadAddress() {
            return uploadAddress;
        }

        public void setUploadAddress(String uploadAddress) {
            this.uploadAddress = uploadAddress;
        }

        public String getCloudVideoId() {
            return cloudVideoId;
        }

        public void setCloudVideoId(String cloudVideoId) {
            this.cloudVideoId = cloudVideoId;
        }

        public String getExpireTime() {
            return expireTime;
        }

        public void setExpireTime(String expireTime) {
            this.expireTime = expireTime;
        }
    }

    public Long getVideoId() {
        return videoId;
    }

    public void setVideoId(Long videoId) {
        this.videoId = videoId;
    }

    public Integer getProvider() {
        return provider;
    }

    public void setProvider(Integer provider) {
        this.provider = provider;
    }

    public Credential getCredential() {
        return credential;
    }

    public void setCredential(Credential credential) {
        this.credential = credential;
    }
}
