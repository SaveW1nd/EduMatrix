package com.edumatrix.system.file.vo;

import java.time.LocalDateTime;

import com.edumatrix.system.file.entity.SysFile;

/**
 * §7.2 查询文件详情的响应体（比 §7.1 多一个 {@code createBy}）。
 *
 * <h2>{@code fileUrl} 恒为 {@code null}，理由是分册里最重的一段</h2>
 * <p>03-01 §7.2 权限段逐字：「<b>本接口只返回元数据，{@code fileUrl} 恒为 {@code null}</b>：
 * 取文件一律走 7.3 下载接口换取时效签名地址。若此处直接下发可访问的直链，
 * <b>7.3 的归属校验将被完全绕过</b>——雪花 ID 在同租户内时间相邻、可近邻枚举，
 * 等于把学生名单（含手机号、监护人手机号）与成绩报表向全租户敞开。」
 *
 * <p>与 {@link FileUploadVO} 同一手法：{@code fileUrl} 是写死的 {@code null} 常量字段，
 * 没有入参也没有 setter —— 让「顺手把地址填上」在类型上就不可能。
 */
public class FileDetailVO {

    private final String fileId;
    private final String fileName;
    /** 见类注释：恒为 null，无入参、无 setter。 */
    private final String fileUrl = null;
    private final Long fileSize;
    private final String fileType;
    private final Integer storage;
    private final String bizType;
    private final String createBy;
    private final LocalDateTime createTime;

    private FileDetailVO(SysFile file) {
        this.fileId = String.valueOf(file.getId());
        this.fileName = file.getFileName();
        this.fileSize = file.getFileSize();
        this.fileType = file.getFileType();
        this.storage = file.getStorage();
        this.bizType = file.getBizType();
        this.createBy = file.getCreateBy() == null ? null : String.valueOf(file.getCreateBy());
        this.createTime = file.getCreateTime();
    }

    public static FileDetailVO of(SysFile file) {
        return new FileDetailVO(file);
    }

    public String getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getFileType() {
        return fileType;
    }

    public Integer getStorage() {
        return storage;
    }

    public String getBizType() {
        return bizType;
    }

    public String getCreateBy() {
        return createBy;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }
}
