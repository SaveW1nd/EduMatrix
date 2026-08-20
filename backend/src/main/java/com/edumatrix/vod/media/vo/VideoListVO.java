package com.edumatrix.vod.media.vo;

import java.time.LocalDateTime;

/**
 * 接口 26 媒资列表的一行（03-03 §7.3）。
 *
 * <p><b>类型上就没有 {@code hlsUrl}</b> —— §7.3 说明逐字：「列表不返回 {@code hls_url}」，
 * 加密 HLS 地址必须经播放凭证签名后才可播放。有字段而不填，下一个人会以为它能用。
 */
public class VideoListVO {

    private Long id;
    private Integer provider;
    private String vodFileId;
    private String videoName;
    private Long ownerNodeId;
    private String ownerNodeName;
    /** 来源：1 自有 2 被授权（§7.3 逐行标识）。 */
    private Integer grantType;
    private Integer duration;
    private String coverUrl;
    private Long sizeBytes;
    private Integer status;
    /** 被多少个未删除课时引用 —— 禁用确认弹窗与删除拦截都看它。 */
    private Integer refLessonCount;
    private Long uploadUserId;
    private String uploadUserName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getProvider() {
        return provider;
    }

    public void setProvider(Integer provider) {
        this.provider = provider;
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

    public Long getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(Long ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

    public String getOwnerNodeName() {
        return ownerNodeName;
    }

    public void setOwnerNodeName(String ownerNodeName) {
        this.ownerNodeName = ownerNodeName;
    }

    public Integer getGrantType() {
        return grantType;
    }

    public void setGrantType(Integer grantType) {
        this.grantType = grantType;
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

    public Integer getRefLessonCount() {
        return refLessonCount;
    }

    public void setRefLessonCount(Integer refLessonCount) {
        this.refLessonCount = refLessonCount;
    }

    public Long getUploadUserId() {
        return uploadUserId;
    }

    public void setUploadUserId(Long uploadUserId) {
        this.uploadUserId = uploadUserId;
    }

    public String getUploadUserName() {
        return uploadUserName;
    }

    public void setUploadUserName(String uploadUserName) {
        this.uploadUserName = uploadUserName;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
