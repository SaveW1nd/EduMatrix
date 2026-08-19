package com.edumatrix.system.file.service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.edumatrix.common.file.FileMeta;
import com.edumatrix.common.file.FileMetaReader;
import com.edumatrix.common.file.InlineFileUrlProvider;
import com.edumatrix.system.file.entity.SysFile;
import com.edumatrix.system.file.mapper.SysFileMapper;

/**
 * {@code common/file} 两个 SPI 的实现：把 {@code system} 领域的文件能力暴露给
 * {@code course}（模块 08）。
 *
 * <p>与 {@code auth/session/AuthAccountProvider} <b>同构</b> —— 接口在 {@code common/}、
 * 实现在提供方领域内、消费方按接口注入。跨领域依赖为零，约定检查③ 零命中。
 *
 * <p><b>本类不含任何判定</b>：能不能下发地址由 {@link FileService#inlineSignedUrl}
 * 决定（D-2 的内联档分级写在 {@code common/file/FileBizType.Exposure} 上）。
 * 一旦这里出现 {@code if} / 循环 / 错误码，就说明逻辑放错了地方。
 *
 * <p>{@link #metaOf} 直接走 {@link SysFileMapper#selectBatchIds} 而不是
 * {@code FileService#detail}：后者带 03-01 §7.3 的完整归属校验，
 * 而 {@code material_attach} 在模块 11 之前是 fail-closed 的（B-3 / F-38），
 * 用它取附件名会让 03-03 §4.2 整条接口挂掉。理由与边界见 {@link FileMetaReader} 类注释。
 */
@Component
public class SystemFileProvider implements InlineFileUrlProvider, FileMetaReader {

    private final FileService fileService;
    private final SysFileMapper sysFileMapper;

    public SystemFileProvider(FileService fileService, SysFileMapper sysFileMapper) {
        this.fileService = fileService;
        this.sysFileMapper = sysFileMapper;
    }

    @Override
    public Optional<String> inlineSignedUrl(Long fileId) {
        return fileId == null ? Optional.empty() : fileService.inlineSignedUrl(fileId);
    }

    @Override
    public List<FileMeta> metaOf(Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyList();
        }
        // 租户条件由插件注入；@TableLogic 自动追加 deleted_at = 0
        List<SysFile> rows = sysFileMapper.selectBatchIds(fileIds);
        return rows.stream()
                .map(f -> new FileMeta(f.getId(), f.getFileName(), f.getFileSize()))
                .toList();
    }
}
