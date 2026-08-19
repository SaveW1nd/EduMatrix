package com.edumatrix.common.file;

import java.util.Optional;

/**
 * D-2 定案的<b>内联签名地址</b>能力 —— 接口在 {@code common/}，实现在 {@code system/file/}。
 *
 * <p>模块 08 的 {@code coverUrl}（03-03 §0.4）与图文正文内嵌图片（D-3）调它。
 * {@code course} 领域不能 import {@code system}（约定检查③），故抽出本接口；
 * <b>实现仍然只有 {@code FileService#inlineSignedUrl} 一处</b>，
 * {@code system/file/service/SystemFileProvider} 只是一层委派。
 *
 * <p><b>能不能下发地址由实现方判定，不由调用方判定</b>：
 * {@code FileService#inlineSignedUrl} 对不在内联档（{@code course_cover} /
 * {@code material_image} / {@code avatar}）的 bizType 一律返回 {@code empty}。
 * 调用方<b>不得</b>自己去查 {@code sys_file.file_url} —— 那一列
 * <b>只存对象键</b>，读出来直接返回等于下发了一条永久直链
 * （{@code 00-通用约定} §7.4 第 1 行「禁止下发长期有效的公开直链」）。
 */
public interface InlineFileUrlProvider {

    /**
     * 现签一个 ≤30 分钟的<b>内联</b>签名地址。
     *
     * @param fileId 文件 ID
     * @return 签名地址；文件不存在 / 跨租户 / bizType 不在内联档 / 本地存储模式 → {@code empty}
     */
    Optional<String> inlineSignedUrl(Long fileId);
}
