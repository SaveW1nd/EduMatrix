package com.edumatrix.common.file;

import java.util.Collection;
import java.util.List;

/**
 * 批量读文件<b>元数据</b>（名 + 大小）—— 接口在 {@code common/}，实现在 {@code system/file/}。
 *
 * <h2>为什么不复用 {@code FileService#detail}</h2>
 * <p>{@code detail} 走 {@code resolveForDownload} 的完整归属校验，而
 * {@code material_attach} 按 B-3 / F-38 定案在模块 11 之前<b>一律 404</b>。
 * 用它取附件名会让图文资料详情整条接口挂掉。
 *
 * <p><b>因此本接口不做归属校验，调用方必须已完成自己的资源级鉴权。</b>
 * 模块 08 的用法是：先按 03-03 §4 的数据权限判定放行这份资料，
 * 再取它 {@code attachment_file_ids} 里那几个 id 的名与大小 ——
 * 调用者本来就能读到这份资料的正文，附件名与字节数不构成新的暴露面。
 * <b>它不下发任何地址</b>（{@link FileMeta} 在类型上保证），
 * 所以 03-01 §7.3 那道归属校验一步都没被绕过。
 */
public interface FileMetaReader {

    /**
     * 按 id 批量取元数据。
     *
     * @return 只含查到的行（不存在 / 已删除 / 跨租户的 id 直接缺席）；顺序不保证
     */
    List<FileMeta> metaOf(Collection<Long> fileIds);
}
