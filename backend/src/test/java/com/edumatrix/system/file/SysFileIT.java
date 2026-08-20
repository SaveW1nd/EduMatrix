package com.edumatrix.system.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.file.FileBizType;
import com.edumatrix.common.file.FileOwnershipRegistry;
import com.edumatrix.common.response.BizException;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestCurrentContextProvider;
import com.edumatrix.system.file.entity.SysFile;
import com.edumatrix.system.file.service.FileService;
import com.edumatrix.system.file.vo.FileDetailVO;

/**
 * 文件三接口（03-01 §7.1~§7.3）的服务层验收。
 *
 * <p>走 {@code FileService} 而不是 MockMvc：三个接口<b>不加 {@code @SaCheckPermission}</b>
 * （契约 §3.1 边界 0 / F-1②），拦人全在 Service 里的四道闸上，
 * 从 HTTP 层打进来只会多绕一层 Sa-Token 而验不到更多东西。
 * HTTP 层的响应头（{@code attachment} + {@code nosniff}）另由 {@code SysFileDownloadIT} 验。
 *
 * <p>测试环境没有 OSS，走 {@code LocalObjectStorage}（{@code storage=1}）——
 * 这是分册明写的两种存储形态之一（03-01 §7.3），不是为测试造的旁路。
 */
@IntegrationTest
class SysFileIT {

    /**
     * 基线数据里的示例机构与它的最高管理员（{@code V202608120000__baseline.sql} 尾部）。
     *
     * <p>用<b>真实存在的</b> {@code sys_user} 行而不是随便编一个 ID：
     * 上传要按 {@code sys_user.user_type} 收窄 bizType（03-01 §7.1
     * 「学生仅限作答附件等受限 bizType」），编的 ID 查不到 {@code user_type} 会一律被拒 ——
     * 那样这组用例会以「10011」的形式全红，而红的原因与被测逻辑无关。
     */
    private static final long TENANT_A = 1953827104412590001L;
    private static final long ADMIN_USER_ID = 1953827104412590102L;
    private static final long ADMIN_NODE_ID = 1953827104412590001L;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileOwnershipRegistry ownershipRegistry;

    @Autowired
    private TestCurrentContextProvider contextProvider;

    @BeforeEach
    void asOrgAdmin() {
        contextProvider.asTenantUser(TENANT_A, ADMIN_USER_ID, ADMIN_NODE_ID);
    }

    private static byte[] xlsxBytes() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("s").createRow(0).createCell(0).setCellValue("x");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    // ====================================================================
    // §7.1 上传
    // ====================================================================

    @Test
    @DisplayName("上传成功：强制重命名为 {fileId}.{规范扩展名}，原始名只进 file_name")
    void uploadRenamesToFileIdAndKeepsOriginalNameSeparately() throws IOException {
        SysFile saved = fileService.upload(
                file("学生名单-高一3班.xlsx", "application/vnd.ms-excel", xlsxBytes()),
                FileBizType.IMPORT_EXCEL.code());

        // 00-通用约定 §7.4：服务端强制重命名为 {fileId}.{规范扩展名}
        assertThat(saved.getFileUrl()).endsWith("/" + saved.getId() + ".xlsx");
        assertThat(saved.getFileUrl()).startsWith(FileBizType.IMPORT_EXCEL.code() + "/");
        // 原始文件名只进 file_name 供展示与下载回填，绝不进路径
        assertThat(saved.getFileName()).isEqualTo("学生名单-高一3班.xlsx");
        assertThat(saved.getFileUrl()).doesNotContain("学生名单");
        assertThat(saved.getFileType()).isEqualTo("xlsx");
    }

    /**
     * 租户归属不能拿"内存里那个实体"来断言。
     *
     * <p>租户插件是在 <b>SQL 层</b>注入 {@code tenant_id} 的（拦截器改写 INSERT 语句），
     * <b>不会回填到实体对象上</b> —— {@code saved.getTenantId()} 落库后仍是 {@code null}。
     * 拿它断言等于什么都没验，而且是"看起来验了"的那种。
     *
     * <p>所以换个方向：<b>切到另一个租户会话，同一个 fileId 必须查不到</b>。
     * 这条走的是与生产完全相同的通道（插件的 {@code WHERE tenant_id = ?}），
     * 而且它直接对应 §7.2/§7.3 的「跨租户返回 404（不暴露存在性）」。
     */
    @Test
    @DisplayName("落库归属本租户：切到别的租户会话后同一个 fileId 查不到（404）")
    void uploadedFileBelongsToTheUploaderTenant() throws IOException {
        SysFile saved = fileService.upload(file("a.xlsx", null, xlsxBytes()), FileBizType.COMMON.code());
        // 同租户查得到
        assertThat(fileService.detail(saved.getId()).getFileId()).isEqualTo(String.valueOf(saved.getId()));

        contextProvider.asTenantUser(9999999999999999L, ADMIN_USER_ID, ADMIN_NODE_ID);
        assertThatThrownBy(() -> fileService.detail(saved.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("Content-Type 与真实类型不符也放行（对 §7.4 的有意收窄，见 F-34）")
    void mismatchedContentTypeIsWarnedNotRejected() throws IOException {
        // 微信内置浏览器对 .xlsx 常报 application/octet-stream —— 作硬闸会大面积误拒
        SysFile saved = fileService.upload(
                file("a.xlsx", "application/octet-stream", xlsxBytes()),
                FileBizType.IMPORT_EXCEL.code());

        assertThat(saved.getFileType()).isEqualTo("xlsx");
    }

    @Test
    @DisplayName("【T-B 端到端】真 .xlsx 改名 .docx → 10011（族内伪装，一级魔数拦不住）")
    void familyLevelSpoofingIsRejected() throws IOException {
        assertThatThrownBy(() -> fileService.upload(
                file("payload.docx", null, xlsxBytes()), FileBizType.COMMON.code()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TYPE_OR_SIZE_INVALID);
    }

    @Test
    @DisplayName("扩展名不在白名单 → 10011（SVG 就是被这一步拦下的）")
    void nonWhitelistedExtensionIsRejected() {
        assertThatThrownBy(() -> fileService.upload(
                file("x.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8)),
                FileBizType.COMMON.code()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("三个服务端生成的 bizType 谁都不能上传（§7.1「不经本接口上传」）")
    void serverGeneratedBizTypesCannotBeUploaded() throws IOException {
        for (FileBizType bizType : new FileBizType[]{
                FileBizType.FAIL_REPORT, FileBizType.CREDENTIAL_SHEET, FileBizType.EXPORT_REPORT}) {
            byte[] content = xlsxBytes();
            assertThatThrownBy(() -> fileService.upload(file("a.xlsx", null, content), bizType.code()))
                    .as("bizType=%s 必须被拒", bizType.code())
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    @DisplayName("未登记的 bizType 字符串 → 10011（bizType 是 form 字段，可伪造）")
    void unknownBizTypeIsRejected() throws IOException {
        byte[] content = xlsxBytes();
        assertThatThrownBy(() -> fileService.upload(file("a.xlsx", null, content), "credential_sheet_x"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("管理员不能传 answer（学生专属档）；学生不能传 import_excel")
    void bizTypeIsNarrowedByRole() throws IOException {
        byte[] content = xlsxBytes();
        // 当前会话是 org_admin（user_type=1），answer 只允许 user_type=3
        assertThatThrownBy(() -> fileService.upload(file("a.xlsx", null, content), FileBizType.ANSWER.code()))
                .isInstanceOf(BizException.class);
        assertThat(FileBizType.ANSWER.allowedUserTypes()).containsExactly(3);
        assertThat(FileBizType.IMPORT_EXCEL.allowedUserTypes()).doesNotContain(3);
        // COMMON 也不对学生开放（§7.1「学生仅限作答附件等受限 bizType」，见 F-37）
        assertThat(FileBizType.COMMON.allowedUserTypes()).doesNotContain(3);
    }

    // ====================================================================
    // §7.2 详情
    // ====================================================================

    @Test
    @DisplayName("详情的 fileUrl 恒为 null（§7.2：下发直链会让 §7.3 的归属校验被完全绕过）")
    void detailNeverExposesUrl() throws IOException {
        SysFile saved = fileService.upload(file("a.png", "image/png", pngBytes()),
                FileBizType.COURSE_COVER.code());

        FileDetailVO detail = fileService.detail(saved.getId());

        assertThat(detail.getFileUrl()).isNull();
        assertThat(detail.getFileId()).isEqualTo(String.valueOf(saved.getId()));
        assertThat(detail.getBizType()).isEqualTo(FileBizType.COURSE_COVER.code());
    }

    // ====================================================================
    // §7.3 归属校验 —— 唯一入口
    // ====================================================================

    /**
     * <b>T-12</b>：需要 checker 而注册表里没有 → 一律 404（fail closed）。
     *
     * <p>这条验的是「默认 DENY」本身。没有它，把 {@code FileOwnershipRegistry}
     * 的默认分支从 {@code return false} 改成 {@code return true}，
     * 其余所有用例<b>照样全绿</b> —— 而那一改的后果是模块 17 上线时
     * 含明文初始密码的 {@code credential_sheet} 对全租户敞开。
     */
    @Test
    @DisplayName("T-12 需要 checker 而未注册的 bizType 下载一律 404（把默认改成放行则本条红）")
    void bizTypesWithoutCheckerAreDeniedByDefault() throws IOException {
        SysFile saved = fileService.upload(
                file("学生名单.xlsx", null, xlsxBytes()), FileBizType.IMPORT_EXCEL.code());

        // 上传者本人也下不了 —— 因为归属判定要读 org_import_task，而模块 17 还没建
        assertThatThrownBy(() -> fileService.resolveForDownload(saved.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("fail-closed 清单恰好是待模块 15/17 注册的那 5 个（material_attach 已由模块 11 摘掉）")
    void pendingCheckerListIsExactlyAsDesigned() {
        assertThat(ownershipRegistry.pendingBizTypes())
                .containsExactlyInAnyOrder(
                        FileBizType.IMPORT_EXCEL,
                        FileBizType.FAIL_REPORT,
                        FileBizType.CREDENTIAL_SHEET,
                        FileBizType.EXPORT_REPORT,
                        FileBizType.ANSWER);
        // MATERIAL_ATTACH 已在模块 11 的 C9 注册（B-3 / F-38 的 fail-closed 就此解除）：
        // course/catalog/MaterialAttachOwnershipChecker，两支判定取自 crs_material 的
        // owner_node_id 列注释（管理端按子树、学生端走所属课时→课程→课程授权）。
        // 解除的【行为判据】在 org/grant/MaterialAttachDownloadIT：
        // 未授权学生 404、已授权学生拿得到文件 —— 两侧都断言，只断言一侧等于闸开了没人知道
    }

    @Test
    @DisplayName("孤儿附件仍然下不了 —— 查不到归属就拒（fail closed 的残留那一半）")
    void orphanMaterialAttachIsStillRejected() throws IOException {
        // 传一个【不属于任何 crs_material】的 material_attach 文件：
        // 模块 11 解除的是「有归属可查时按授权判」，而【查不到归属】这一侧仍然拒 ——
        // 返回 true 才是危险的那一侧（等于「查不到归属就放行」）
        SysFile saved = fileService.upload(
                file("讲义.pdf", "application/pdf", pdfBytes()), FileBizType.MATERIAL_ATTACH.code());

        assertThatThrownBy(() -> fileService.resolveForDownload(saved.getId()))
                .as("本条【不能】用来证明 fail-closed 还在：checker 写死 false 它也绿。"
                        + "真正的判据是 org/grant/MaterialAttachDownloadIT 那两条")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("【保留侧】不需要 checker 的 bizType 正常可下（否则把 canAccess 写死 false 也全绿）")
    void bizTypesWithoutOwnershipRequirementRemainDownloadable() throws IOException {
        SysFile saved = fileService.upload(file("封面.png", "image/png", pngBytes()),
                FileBizType.COURSE_COVER.code());

        SysFile resolved = fileService.resolveForDownload(saved.getId());

        assertThat(resolved.getId()).isEqualTo(saved.getId());
        assertThat(FileBizType.COURSE_COVER.requiresOwnershipChecker()).isFalse();
    }

    @Test
    @DisplayName("不存在 / 跨租户 → 404（不暴露存在性）")
    void missingOrCrossTenantIsNotFound() {
        assertThatThrownBy(() -> fileService.resolveForDownload(1234567890123456789L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ====================================================================
    // D-2：内联签名地址
    // ====================================================================

    @Test
    @DisplayName("D-2 内联档只对三种 bizType 开放，其余一律 empty（由 Service 保证而非调用方）")
    void inlineSignedUrlIsRestrictedToThreeBizTypes() throws IOException {
        SysFile cover = fileService.upload(file("封面.png", "image/png", pngBytes()),
                FileBizType.COURSE_COVER.code());
        SysFile attach = fileService.upload(file("讲义.pdf", "application/pdf", pdfBytes()),
                FileBizType.MATERIAL_ATTACH.code());

        // material_attach 不在内联档 —— D-2 定案：只返 fileId + fileName + fileSize
        assertThat(fileService.inlineSignedUrl(attach.getId())).isEmpty();

        // course_cover 在内联档；本地存储没有签名地址，故这里恒 empty（LocalObjectStorage 的取舍）。
        // 生产走 OSS 时才有值，签名参数由 OssPresignParamsTest 单独验。
        assertThat(FileBizType.COURSE_COVER.exposure()).isEqualTo(FileBizType.Exposure.SIGNED_INLINE);
        assertThat(fileService.inlineSignedUrl(cover.getId())).isEmpty();
    }

    // ====================================================================
    // 素材
    // ====================================================================

    private static byte[] pngBytes() {
        byte[] out = new byte[64];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(head, 0, out, 0, head.length);
        return out;
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
    }
}
