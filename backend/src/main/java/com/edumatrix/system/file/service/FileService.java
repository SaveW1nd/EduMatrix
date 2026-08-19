package com.edumatrix.system.file.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.file.FileBizType;
import com.edumatrix.common.file.FileConstants;
import com.edumatrix.common.file.FileKeys;
import com.edumatrix.common.file.FileOwnershipChecker;
import com.edumatrix.common.file.FileOwnershipRegistry;
import com.edumatrix.common.file.FileTypeDetector;
import com.edumatrix.common.file.ObjectStorage;
import com.edumatrix.common.id.IdWorker;
import com.edumatrix.common.metrics.MetricsRegistry;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.system.file.entity.SysFile;
import com.edumatrix.system.file.mapper.SysFileMapper;
import com.edumatrix.system.file.vo.FileDetailVO;
import com.edumatrix.system.user.mapper.SysUserMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 文件三接口的业务实现（03-01 §7.1 上传 / §7.2 详情 / §7.3 下载）。
 *
 * <h2>三个接口都不加 {@code @SaCheckPermission} —— 那靠什么拦人</h2>
 * <p>契约 §3.1 三条边界第 0 条逐字：「学生端接口一律不加 {@code @SaCheckPermission}，
 * 因而不发 {@code perms}……这些接口的归属校验本就各自完备（<b>文件走 §7.4 的
 * {@code bizType} 校验</b>……）」，F-1 定案② 落地于此。
 * {@code system:file:*} 的 {@code perms} 只用于 A22 页面菜单显隐。
 *
 * <p>拦人靠四道，<b>顺序固定</b>（下载接口的完整判定顺序表见 {@link #resolveForDownload}）：
 * <ol>
 *   <li>Sa-Token 拦截器：未登录 401（三个接口都不在 {@code 00-通用约定} §2.3 的四条白名单里）；</li>
 *   <li><b>频次闸</b>：{@link FileUploadRateLimiter}，60 秒 20 次，超限 429（D-5）；</li>
 *   <li><b>bizType 白名单 + 按角色收窄</b>：{@code bizType} 是 form 字段、<b>可伪造</b>；</li>
 *   <li><b>归属校验</b>：{@link FileOwnershipRegistry}，需要 checker 而没注册的一律拒。</li>
 * </ol>
 *
 * <h2>上传的顺序为什么是这个顺序</h2>
 * <p>频次闸在最前 —— 否则刷子仍然能让服务端把 100MB 整读一遍再拒。
 * 落 OSS 在<b>最后</b>：类型判定要读完整字节（ZIP 的中央目录在文件<b>末尾</b>），
 * 先传后判等于把一份未经校验的文件放进了桶里，之后即使拒了也要再删一次，
 * 而"删失败"这条路径没人测。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /** {@code sys_file.file_name} 是 {@code VARCHAR(255)}。 */
    private static final int MAX_FILE_NAME = 255;

    /** {@code sys_user.user_type}：机构管理员（契约 §3 角色表：0 超管 1 管理员 2 教师 3 学生）。 */
    private static final int USER_TYPE_ORG_ADMIN = 1;

    private final SysFileMapper sysFileMapper;
    private final SysUserMapper sysUserMapper;
    private final ObjectStorage objectStorage;
    private final FileOwnershipRegistry ownershipRegistry;
    private final FileUploadRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;

    public FileService(SysFileMapper sysFileMapper,
                       SysUserMapper sysUserMapper,
                       ObjectStorage objectStorage,
                       FileOwnershipRegistry ownershipRegistry,
                       FileUploadRateLimiter rateLimiter,
                       MeterRegistry meterRegistry) {
        this.sysFileMapper = sysFileMapper;
        this.sysUserMapper = sysUserMapper;
        this.objectStorage = objectStorage;
        this.ownershipRegistry = ownershipRegistry;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    // =====================================================================
    // §7.1 上传文件
    // =====================================================================

    /**
     * 上传。任何一步不过一律 {@code 10011}（03-01 §7.1 的唯一业务错误码）。
     *
     * <p><b>响应里的 {@code fileUrl} 恒为 {@code null}</b>（§7.1 响应字段说明逐字）——
     * 由 VO 保证，本方法根本不产出地址。
     */
    public SysFile upload(MultipartFile multipartFile, String declaredBizType) {
        Long userId = TenantHelper.getUserId();
        rateLimiter.check(userId);

        FileBizType bizType = resolveUploadBizType(declaredBizType, userId);
        String declaredExt = FileTypeDetector.extensionOf(multipartFile.getOriginalFilename());
        // 扩展名不在白名单就没必要落临时文件了 —— 省掉一次 100MB 的磁盘写
        String canonicalDeclared = FileTypeDetector.canonical(declaredExt)
                .orElseThrow(() -> typeOrSizeInvalid("扩展名不在白名单：" + declaredExt));

        Path temp = writeTempFile(multipartFile);
        try {
            FileTypeDetector.DetectedType detected = FileTypeDetector.detect(temp)
                    .orElseThrow(() -> typeOrSizeInvalid("文件头魔数无法判定为白名单内的类型"));

            // 【核心】以魔数为准且必须与扩展名一致（00-通用约定 §7.4 第 3 行）。
            // 族内判别已在 FileTypeDetector 里做过，所以真 .xlsx 改名 .docx 会在这里被拒
            if (!detected.canonicalExt().equals(canonicalDeclared)) {
                throw typeOrSizeInvalid("文件头魔数判定为 " + detected.canonicalExt()
                        + "，与扩展名 " + canonicalDeclared + " 不一致");
            }

            long size = fileSize(temp);
            long limit = sizeLimitOf(bizType, detected);
            if (size <= 0 || size > limit) {
                throw typeOrSizeInvalid("文件大小 " + size + " 超出上限 " + limit);
            }

            warnOnContentTypeMismatch(multipartFile, detected);

            return persist(multipartFile, bizType, detected, size, temp);
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传文件失败", e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * bizType 白名单 + 按角色收窄。{@code bizType} 是 form 字段，<b>假定它是攻击者写的</b>。
     *
     * <p>三个值<b>谁都不能传</b>（03-01 §7.1 逐字「由服务端异步任务生成登记，
     * 不经本接口上传」）：{@code fail_report} / {@code credential_sheet} / {@code export_report}。
     * 不堵的话，学生可以自制一个 xlsx 标成 {@code credential_sheet} ——
     * 危害有限（他只能下回自己的文件），但会污染 {@code TempFileCleanupJob} 的白名单语义，
     * 并让 A22 页面出现一份假的「账号密码表」。
     */
    private FileBizType resolveUploadBizType(String declared, Long userId) {
        String value = declared == null || declared.isBlank() ? FileBizType.COMMON.code() : declared.trim();
        FileBizType bizType = FileBizType.of(value)
                .orElseThrow(() -> typeOrSizeInvalid("未登记的 bizType：" + value));
        if (!bizType.uploadable()) {
            throw typeOrSizeInvalid("bizType " + bizType.code() + " 由服务端任务生成，不经本接口上传");
        }
        Integer userType = userId == null ? null : sysUserMapper.selectUserTypeById(userId);
        if (userType == null || !bizType.allowedUserTypes().contains(userType)) {
            throw typeOrSizeInvalid("当前角色不允许上传 bizType " + bizType.code());
        }
        return bizType;
    }

    /** 上限 = min(bizType 档, 扩展名档)。两档的出处见 {@code FileConstants} 各常量注释。 */
    private static long sizeLimitOf(FileBizType bizType, FileTypeDetector.DetectedType detected) {
        long byBizType = bizType.maxSize();
        long byExtension = detected.isImage() ? FileConstants.MAX_SIZE_IMAGE : FileConstants.MAX_SIZE_DEFAULT;
        return Math.min(byBizType, byExtension);
    }

    /**
     * 第三重校验：请求 {@code Content-Type}。<b>只 WARN，不拒绝</b>。
     *
     * <p>这是对 {@code 00-通用约定} §7.4 的一处<b>有意收窄</b>，已登记 F-34：
     * §7.4 原文对"必须一致"只约束了「魔数 vs 扩展名」，没有对 {@code Content-Type}
     * 提同样要求；而浏览器（尤其 PRD §7.4 点名的一级适配目标微信内置浏览器）
     * 对 {@code .xlsx} 经常上报 {@code application/octet-stream}，作硬闸会大面积误拒。
     */
    private void warnOnContentTypeMismatch(MultipartFile multipartFile,
                                           FileTypeDetector.DetectedType detected) {
        String declared = multipartFile.getContentType();
        if (declared != null && detected.mimeType() != null
                && !declared.equalsIgnoreCase(detected.mimeType())) {
            log.warn("上传 Content-Type 与魔数判定不一致（不拒绝，见 F-34）：声明={} 实际={} 文件名={}",
                    declared, detected.mimeType(), multipartFile.getOriginalFilename());
        }
    }

    private SysFile persist(MultipartFile multipartFile, FileBizType bizType,
                            FileTypeDetector.DetectedType detected, long size, Path temp) {
        long fileId = IdWorker.nextId();
        String key = FileKeys.build(bizType, fileId, detected.canonicalExt());
        objectStorage.put(key, temp, detected.mimeType());

        SysFile entity = new SysFile();
        entity.setId(fileId);
        entity.setFileName(truncate(multipartFile.getOriginalFilename(), MAX_FILE_NAME));
        entity.setFileUrl(key);
        entity.setFileSize(size);
        entity.setFileType(detected.canonicalExt());
        entity.setStorage(objectStorage.storageType());
        entity.setBizType(bizType.code());
        sysFileMapper.insert(entity);
        return entity;
    }

    // =====================================================================
    // §7.2 查询文件详情
    // =====================================================================

    /**
     * 详情。<b>归属校验与 §7.3 下载接口完全一致，不得放宽</b>（§7.2 权限段逐字）。
     *
     * <p>§7.2 那段的理由值得原样留在这里：「若此处直接下发可访问的直链，
     * 7.3 的归属校验将被<b>完全绕过</b>——雪花 ID 在同租户内时间相邻、可近邻枚举，
     * 等于把学生名单（含手机号、监护人手机号）与成绩报表向全租户敞开」。
     * 所以 {@code fileUrl} 由 {@link FileDetailVO} <b>恒置 null</b>。
     */
    public FileDetailVO detail(Long fileId) {
        SysFile file = resolveForDownload(fileId);
        return FileDetailVO.of(file);
    }

    // =====================================================================
    // §7.3 下载文件 —— 归属校验的唯一入口
    // =====================================================================

    /**
     * 下载的<b>完整判定顺序表</b>（仿 03-02 §3.4 的写法，逐条对应错误码）。
     *
     * <table border="1">
     *   <caption>判定顺序（任一不过即终止）</caption>
     *   <tr><th>#</th><th>判定</th><th>不通过</th><th>依据</th></tr>
     *   <tr><td>1</td><td>已登录</td><td><b>401</b></td>
     *       <td>Sa-Token 拦截器；本接口不在 {@code 00-通用约定} §2.3 的四条白名单里</td></tr>
     *   <tr><td>2</td><td>按 id 查到行（租户条件由插件注入）</td><td><b>404</b></td>
     *       <td>§7.3「跨租户返回 404（不暴露存在性）」</td></tr>
     *   <tr><td>3</td><td>{@code deleted_at = 0}（{@code @TableLogic} 自动）</td><td><b>404</b></td>
     *       <td>§7.3「已逻辑删除（含导出报表超 7 天保留期被清理）……均返回 HTTP 404」</td></tr>
     *   <tr><td>4</td><td>{@code biz_type} 在已登记字典内</td><td><b>404</b></td>
     *       <td>未登记值 = 数据被篡改，按不存在处理。<b>不返回 {@code 10011}</b>——那是上传侧的码</td></tr>
     *   <tr><td>5</td><td><b>bizType 归属校验</b>（{@link FileOwnershipRegistry}）</td><td><b>404</b></td>
     *       <td>§7.3 + {@code 00-通用约定} §7.4「归属校验的唯一入口」</td></tr>
     *   <tr><td>6</td><td>{@code storage} 与当前存储实现一致</td><td><b>404</b> + ERROR</td>
     *       <td>库里 {@code storage=2} 而进程只配了本地存储 → 这一行<b>取不到</b>。
     *           静默返回空内容比响亮失败糟得多</td></tr>
     * </table>
     *
     * <p><b>4 / 5 一律 404 而不是 403</b>：{@code 00-通用约定} §2.4 越界三分法
     * 「访问<b>路径上的资源</b>而该资源不在我的子树内 → 404，不暴露存在性」。
     * 并按契约 §7.1 打 {@code api_permission_denied_total{code="404"}} +
     * WARN（带 {@code traceId + userId + 目标对象 ID}，§7.1「日志分级」逐字要求）。
     */
    public SysFile resolveForDownload(Long fileId) {
        SysFile file = sysFileMapper.selectById(fileId);
        if (file == null) {
            throw notFound(fileId, "文件不存在、已删除或跨租户");
        }
        FileBizType bizType = FileBizType.of(file.getBizType())
                .orElseThrow(() -> notFound(fileId, "未登记的 biz_type：" + file.getBizType()));

        Long userId = TenantHelper.getUserId();
        boolean orgAdmin = isOrgAdmin(userId);
        FileOwnershipChecker.FileRef ref = new FileOwnershipChecker.FileRef(
                file.getId(), bizType, file.getCreateBy(), file.getTenantId());
        if (!ownershipRegistry.canAccess(ref, userId, orgAdmin)) {
            throw notFound(fileId, "bizType " + bizType.code() + " 归属校验不通过");
        }
        if (file.getStorage() == null || file.getStorage() != objectStorage.storageType()) {
            log.error("sys_file.storage={} 与当前存储实现 storageType={} 不一致，取不到内容 fileId={}。"
                            + "这是部署配置问题（本地 / OSS 切换后遗留行），不是权限问题",
                    file.getStorage(), objectStorage.storageType(), fileId);
            throw notFound(fileId, "存储后端不匹配");
        }
        return file;
    }

    /**
     * {@code storage=2}：签名地址（302 目标）。{@code storage=1}：{@code empty}，调用方回流。
     *
     * <p>{@link ObjectStorage.Disposition#ATTACHMENT} 承载 D-4 定案 ——
     * 见 {@code ObjectStorage.Disposition} 的类注释。
     */
    public Optional<String> signedDownloadUrl(SysFile file) {
        return objectStorage.presignedUrl(file.getFileUrl(), file.getFileName(),
                mimeOf(file), ObjectStorage.Disposition.ATTACHMENT, FileConstants.SIGNED_URL_TTL);
    }

    /** {@code storage=1} 的回流（03-01 §7.3「直接返回文件流」）。 */
    public InputStream openStream(SysFile file) {
        return objectStorage.openStream(file.getFileUrl());
    }

    // =====================================================================
    // D-2：供其他模块下发内联签名地址（course_cover / material_image / avatar）
    // =====================================================================

    /**
     * <b>D-2 定案</b>：给必须由浏览器内联渲染的三种 bizType 现签一个 ≤30 分钟的地址。
     *
     * <p>模块 08 的 {@code coverUrl}（03-03 §0.4）与富文本正文里的图片（D-3）调它。
     * 其余 bizType 传进来一律返回 {@code empty} —— <b>由本方法而不是调用方保证</b>，
     * 否则「哪些能下发地址」就会散落在每个消费方里，而漏一处的表现是一条永久可访问的直链。
     *
     * <p>本地存储模式下恒为 {@code empty}（{@code LocalObjectStorage} 没有签名地址），
     * 于是开发环境的封面不显示。生产一律 OSS，不受影响。
     *
     * @param fileId 文件 ID；不存在、跨租户、bizType 不在内联档 → {@code empty}
     */
    public Optional<String> inlineSignedUrl(Long fileId) {
        SysFile file = sysFileMapper.selectById(fileId);
        if (file == null) {
            return Optional.empty();
        }
        Optional<FileBizType> bizType = FileBizType.of(file.getBizType());
        if (bizType.isEmpty() || bizType.get().exposure() != FileBizType.Exposure.SIGNED_INLINE) {
            return Optional.empty();
        }
        if (file.getStorage() == null || file.getStorage() != objectStorage.storageType()) {
            return Optional.empty();
        }
        return objectStorage.presignedUrl(file.getFileUrl(), file.getFileName(), mimeOf(file),
                ObjectStorage.Disposition.INLINE, FileConstants.SIGNED_URL_TTL);
    }

    /** {@code TempFileCleanupJob} 用：物理删对象。 */
    public void deleteObject(String key) {
        objectStorage.delete(key);
    }

    /** 当前存储实现的 {@code storage} 取值，供 Job 比对。 */
    public int storageType() {
        return objectStorage.storageType();
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private boolean isOrgAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        Integer userType = sysUserMapper.selectUserTypeById(userId);
        return userType != null && userType == USER_TYPE_ORG_ADMIN;
    }

    private static String mimeOf(SysFile file) {
        return FileTypeDetector.mimeOf(file.getFileType()).orElse("application/octet-stream");
    }

    private BizException notFound(Long fileId, String reason) {
        // 契约 §7.1 日志分级：越权拒绝一律 WARN，带 traceId（MDC 自动）+ userId + 目标对象 ID
        log.warn("文件访问被拒 fileId={} userId={} 原因={}", fileId, TenantHelper.getUserId(), reason);
        meterRegistry.counter(MetricsRegistry.API_PERMISSION_DENIED_TOTAL,
                MetricsRegistry.TAG_CODE, "404").increment();
        return BizException.notFound(fileId);
    }

    private static BizException typeOrSizeInvalid(String reason) {
        log.warn("上传被拒（10011）：{}", reason);
        return BizException.of(ErrorCode.FILE_TYPE_OR_SIZE_INVALID);
    }

    private static Path writeTempFile(MultipartFile multipartFile) {
        try {
            Path temp = Files.createTempFile("edumatrix-upload-", ".bin");
            multipartFile.transferTo(temp.toFile());
            return temp;
        } catch (IOException e) {
            throw new UncheckedIOException("落临时文件失败", e);
        }
    }

    private static long fileSize(Path path) throws IOException {
        return Files.size(path);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除上传临时文件失败 {}", path, e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 供 {@code Duration} 相关计算复用，避免调用方各自 new 一个（当前只有 Job 用）。 */
    public static Duration retention() {
        return FileConstants.TEMP_FILE_RETENTION;
    }
}
