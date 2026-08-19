package com.edumatrix.common.file;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * 对象存储的能力。SPI：本接口在 {@code common/}，OSS 实现在 {@code integration/aliyun/OssClient}
 * （05-工程结构.md §G2：{@code integration/aliyun/} 里的 Client 是部署级配置的<b>全部</b>消费方，
 * 「任何业务包里出现 {@code @Value("${ALIYUN_...}")} 都是越界」）。
 *
 * <h2>为什么是 SPI 而不是让 {@code system/file} 直接用 OSS SDK</h2>
 * <p>两条理由，第二条是硬的：
 * <ol>
 *   <li>集成测试连的是 {@code deploy/docker-compose.dev.yml} 起的 MySQL 与 Redis
 *       （{@code support/IntegrationTest} 类注释），<b>没有 OSS</b>。直接用 SDK 则
 *       文件三接口一条 IT 都写不了；
 *   <li>{@code sys_file.storage} 的 DDL 就是 {@code 1本地 2OSS} 两个取值，
 *       03-01 §7.3 也按这两个取值分了两条响应形态（本地回流 / OSS 302）。
 *       <b>两种存储是分册明写的，不是我为了测试造出来的抽象。</b>
 * </ol>
 *
 * <h2>{@link #presignedUrl} 返回 {@code Optional}，不是"总能拿到 URL"</h2>
 * <p>本地存储没有签名地址，返回 {@code Optional.empty()}；调用方据此走"回流"分支。
 * <b>不要为了让两种实现"长得一样"而给本地存储编一个 URL 出来</b> ——
 * 那会让开发环境与生产的行为分叉，而分叉点恰好在鉴权上。
 *
 * <h2>有效期一律由调用方传，且只有一个合法值</h2>
 * <p>{@code 00-通用约定} §7.4「有效期 ≤30 分钟」、03-01 §7.3「有效期 30 分钟」、
 * 03-05 §4.8「有效期 30 分钟」是<b>同一个数字的三处登记</b>。
 * 常量在 {@link FileConstants#SIGNED_URL_TTL}，<b>不做成配置项</b> ——
 * 做成可配等于允许把它调成 24 小时而没有任何人知道。
 */
public interface ObjectStorage {

    /**
     * 本实现对应的 {@code sys_file.storage} 取值：{@code 1} 本地 / {@code 2} OSS（DDL 逐字）。
     *
     * <p>上传时按它写库；下载时用它与库里的值<b>比对</b>，不一致即 404 + ERROR ——
     * 一行 {@code storage=2} 的记录在只配了本地存储的进程里是<b>取不到的</b>，
     * 静默返回空内容比响亮失败糟得多。
     */
    int storageType();

    /**
     * 落盘 / 落桶。{@code key} 由 {@link FileKeys} 统一生成，调用方不得自造。
     *
     * @param contentType 规范扩展名推导出的 MIME，<b>不是</b>请求里那个（请求头可伪造，
     *                    见 {@code FileTypeDetector} 类注释）
     */
    void put(String key, Path source, String contentType);

    /** 物理删除。{@code TempFileCleanupJob} 先调它、成功后才改库（顺序反了会留下删不掉的孤儿对象）。 */
    void delete(String key);

    /**
     * 签名下载地址。本地实现返回 {@code Optional.empty()}。
     *
     * @param disposition {@link Disposition#ATTACHMENT} 强制下载（§7.3 下载接口）；
     *                    {@link Disposition#INLINE} 供 {@code <img>} 内联渲染
     *                    （D-2 定案的 {@code course_cover} / {@code material_image} / {@code avatar} 三档）
     */
    Optional<String> presignedUrl(String key, String downloadFileName, String contentType,
                                  Disposition disposition, Duration ttl);

    /** 读回内容。{@code storage=1} 的下载走它（03-01 §7.3「直接返回文件流」）。 */
    InputStream openStream(String key);

    /**
     * 响应形态。
     *
     * <p><b>{@code ATTACHMENT} 这一档承载的是 D-4 定案</b>：{@code 00-通用约定} §7.4 要求
     * 「下载统一 {@code Content-Disposition: attachment} + {@code X-Content-Type-Options: nosniff}」，
     * 而 03-01 §7.3 的 OSS 路径是 <b>302 重定向</b> —— 重定向之后浏览器请求的是 OSS，
     * 我们自己写的响应头<b>一个都不生效</b>，而 {@code sys_file.storage} 的 DDL 默认值就是 2。
     * 处置：把 {@code response-content-disposition} 与 {@code response-content-type}
     * 作为<b>参与签名</b>的 query 参数附在签名地址上（改一个字签名就失效）；
     * {@code nosniff} 在 302 路径上确实拿不到，需方已知悉并接受（D-4 选项 i）。
     */
    enum Disposition {
        /** 强制下载：{@code attachment} + {@code application/octet-stream}。 */
        ATTACHMENT,
        /** 内联渲染：{@code inline} + 真实 MIME。仅 D-2 允许的三种 bizType 可用。 */
        INLINE
    }
}
