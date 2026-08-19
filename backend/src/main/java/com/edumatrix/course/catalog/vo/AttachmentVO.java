package com.edumatrix.course.catalog.vo;

/**
 * 图文资料附件（03-03 §4.2 / §6.3 的 {@code attachments[]}）。
 *
 * <p><b>只有 {@code fileId} / {@code fileName} / {@code fileSize}，没有 {@code fileUrl}</b>
 * —— D-2 定案：{@code material_attach} 不在内联档，取文件一律走 03-01 §7.3 下载接口。
 * 本轮已同步删除 §4.2 与 §6.3 响应示例里的 {@code fileUrl} 字段。
 *
 * <p><b>没有 URL 字段是类型层面的保证</b>，与 {@code FileUploadVO} / {@code FileDetailVO}
 * 把 {@code fileUrl} 写成常量 {@code null} 是同一手法：让「顺手把地址填上」不可能发生。
 *
 * <p>⚠ 模块 11 之前，这些附件经 03-01 §7.3 下载会返回 <b>404</b>
 * （B-3 / F-38 的 fail-closed）——<b>这是设计行为，不是模块 08 的 bug</b>。
 */
public class AttachmentVO {

    private Long fileId;

    private String fileName;

    private Long fileSize;

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
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
}
