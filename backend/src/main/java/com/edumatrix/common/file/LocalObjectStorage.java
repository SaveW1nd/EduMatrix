package com.edumatrix.common.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 本地磁盘存储（{@code sys_file.storage = 1}，DDL 逐字「1本地 2OSS」）。
 *
 * <p><b>只在没有配 OSS 桶时生效</b>。与 {@code OssClient} 的条件<b>互为反面且都写在同一个属性上</b>
 * （{@code edumatrix.file.oss.bucket} 非空 → OSS；空 → 本类）。
 * 刻意不用 {@code @ConditionalOnMissingBean}：普通 {@code @Component} 之间的条件求值顺序
 * 没有保证，两个都装配或都不装配都可能发生，而那是启动期才暴露的随机故障。
 * 它存在的理由是集成测试：{@code support/IntegrationTest} 连的是 docker-compose 起的
 * MySQL 与 Redis，<b>没有 OSS</b>；没有本类则文件三接口一条 IT 都写不了。
 *
 * <h2>{@link #presignedUrl} 恒为 {@code empty}，这是有意的</h2>
 * <p>本地存储没有签名地址。<b>不为了"两种实现长得一样"而编一个 URL 出来</b> ——
 * 那会让开发环境与生产在<b>鉴权</b>这一点上分叉。
 * 连带后果要说清：<b>本地存储模式下 D-2 的 {@code coverUrl} 为 {@code null}</b>，
 * 开发环境的课程封面不显示。生产一律 OSS，不受影响。
 *
 * <h2>路径穿越</h2>
 * <p>{@code key} 由 {@link FileKeys} 生成、含用户输入的部分只有雪花 ID 与规范扩展名，
 * 理论上穿不出去。但这里仍然做一次 {@code normalize + startsWith} 校验：
 * <b>「调用方保证」不是保证</b>，而这一处的代价是两行代码。
 */
@Component
@ConditionalOnExpression("'${edumatrix.file.oss.bucket:}'.trim() == ''")
public class LocalObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectStorage.class);

    private final Path root;

    public LocalObjectStorage(@Value("${edumatrix.file.local-root:${java.io.tmpdir}/edumatrix-files}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        log.info("对象存储 = 本地磁盘 root={}（生产应为 OSS；storage 将写 1）", this.root);
    }

    @Override
    public int storageType() {
        return 1;
    }

    @Override
    public void put(String key, Path source, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("本地存储写入失败 key=" + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("本地存储删除失败 key=" + key, e);
        }
    }

    @Override
    public Optional<String> presignedUrl(String key, String downloadFileName, String contentType,
                                         Disposition disposition, Duration ttl) {
        return Optional.empty();
    }

    @Override
    public InputStream openStream(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("本地存储读取失败 key=" + key, e);
        }
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法对象键（路径穿越）：" + key);
        }
        return target;
    }
}
