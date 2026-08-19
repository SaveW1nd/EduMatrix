package com.edumatrix.common.file;

/**
 * 文件的展示元数据 —— <b>只有 id / 名 / 大小，没有任何地址</b>。
 *
 * <p>D-2 定案：{@code material_attach} 不在内联档，
 * 03-03 §4.2 / §6.3 的 {@code attachments[]} 只返回
 * {@code fileId} / {@code fileName} / {@code fileSize}，取文件一律走 03-01 §7.3。
 * <b>本 record 里没有 URL 字段，是为了让「顺手把地址填上」在类型上就不可能</b>
 * —— 与 {@code FileUploadVO} / {@code FileDetailVO} 把 {@code fileUrl} 写成
 * 常量 {@code null} 是同一手法。
 */
public record FileMeta(Long fileId, String fileName, Long fileSize) {
}
