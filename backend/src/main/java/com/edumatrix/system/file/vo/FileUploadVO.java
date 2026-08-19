package com.edumatrix.system.file.vo;

import java.time.LocalDateTime;

import com.edumatrix.system.file.entity.SysFile;

/**
 * §7.1 上传文件的响应体（字段集逐条对齐 03-01 §7.1「响应字段说明」）。
 *
 * <h2>{@code fileUrl} <b>恒为 {@code null}</b>，且是在类型上恒为 null</h2>
 * <p>§7.1 响应字段说明逐字：「{@code fileUrl} | string | <b>恒为 {@code null}</b>……
 * 文件内容一律经 7.3 下载接口获取，<b>不在本接口下发可访问地址</b>」。
 *
 * <p>本 VO <b>没有 {@code fileUrl} 的构造入参</b> —— 它是一个写死的 {@code null} 常量字段。
 * 这不是啰嗦：只要留一个 setter，将来某个人「顺手把地址填上」就是一次
 * {@code 00-通用约定} §7.4 第 2 行的违反，而表现是接口 200、字段齐全、
 * 归属校验被完全绕过。<b>让它在类型上就不可能被填</b>。
 */
public class FileUploadVO {

    private final String fileId;
    private final String fileName;
    /** 见类注释：恒为 null，无入参、无 setter。 */
    private final String fileUrl = null;
    private final Long fileSize;
    private final String fileType;
    private final Integer storage;
    private final String bizType;
    private final LocalDateTime createTime;

    private FileUploadVO(SysFile file) {
        this.fileId = String.valueOf(file.getId());
        this.fileName = file.getFileName();
        this.fileSize = file.getFileSize();
        this.fileType = file.getFileType();
        this.storage = file.getStorage();
        this.bizType = file.getBizType();
        this.createTime = file.getCreateTime();
    }

    public static FileUploadVO of(SysFile file) {
        return new FileUploadVO(file);
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }
}
