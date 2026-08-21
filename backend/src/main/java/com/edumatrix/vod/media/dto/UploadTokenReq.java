package com.edumatrix.vod.media.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接口 25 获取视频上传凭证（03-03 §7.1）。
 *
 * <p><b>请求体不接受 {@code ownerNodeId}</b>（§7.3 说明逐字：由服务端强制写入上传者所在节点）。
 * 这不是「忽略它」，是<b>类型上就没有这个字段</b> —— 有字段而忽略，下一个人会以为它能用。
 */
public class UploadTokenReq {

    @NotBlank(message = "媒资名称不能为空")
    @Size(min = 1, max = 200, message = "媒资名称长度需在 1~200 字符")
    private String videoName;

    /** 原始文件名（含扩展名），云端据此识别封装格式。 */
    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名过长")
    private String fileName;

    /**
     * 文件大小（字节），上限 4GB（PRD F2-3 规则 5）。
     *
     * <p><b>{@code @NotNull} 与 {@code @Min} 都要有</b>：只写 {@code @Min} 时不传该字段
     * 会直接通过（本项目已就 {@code @AssertTrue} 踩过同一个坑）。
     */
    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于 0")
    @Max(value = 4294967296L, message = "单文件不得超过 4GB")
    private Long fileSize;

    /**
     * 续签 / 重传时传入已有媒资 ID。
     *
     * <p><b>它是「请求体参数」而不是「路径资源」</b>，所以查不到时返 {@code 20015} 而不是 404 ——
     * F-42 定案的边界：用户主动选了一个对象、选错了要明确提示，返 404 会让他以为端点写错了。
     */
    private Long videoId;

    public String getVideoName() {
        return videoName;
    }

    public void setVideoName(String videoName) {
        this.videoName = videoName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Long getVideoId() {
        return videoId;
    }

    public void setVideoId(Long videoId) {
        this.videoId = videoId;
    }

    /**
     * 是否加密上传（F-114，需方 2026-08-21 定案：<b>上传时可选</b>）。
     *
     * <p>{@code true} 走加密模板组（阿里云私有加密），{@code false} 或不传走不加密组。
     * <b>加密属性跟着视频走、不跟着课程走</b> —— 同一棵课程树下可以混着加密与不加密的视频，
     * 同一个视频也可以被挂到多个课时。
     *
     * <p><b>只在新建时有效</b>：续传（带 {@code videoId}）不改模板组，
     * 那会让同一个视频前后两次用不同的组。
     */
    private Boolean encrypted;

    public Boolean getEncrypted() {
        return encrypted;
    }

    public void setEncrypted(Boolean encrypted) {
        this.encrypted = encrypted;
    }
}
