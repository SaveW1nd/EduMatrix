package com.edumatrix.system.file.controller;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.R;
import com.edumatrix.system.file.entity.SysFile;
import com.edumatrix.system.file.service.FileService;
import com.edumatrix.system.file.vo.FileDetailVO;
import com.edumatrix.system.file.vo.FileUploadVO;

/**
 * 文件管理三接口（03-01 §7.1~§7.3）。<b>接口总数不变</b>：这三条早在 03-01 目录表里。
 *
 * <h2>⚠ 三个接口都<b>不加</b> {@code @SaCheckPermission}</h2>
 * <p>契约 §3.1「三条边界」第 0 条 + F-1 定案②。原文逐字：
 * 「学生端接口一律不加 {@code @SaCheckPermission}，因而不发 {@code perms}。
 * 只按角色（{@code sys_user.user_type}）与数据权限（子树 / 本人）判定。
 * 这些接口的归属校验本就各自完备（<b>文件走 §7.4 的 {@code bizType} 校验</b>……），
 * 再加一道 {@code perms} 门只是多一处会配错的地方 —— 而配错的表现
 * （接口 200、{@code perms} 空数组、学生传不了作答附件）与 §2.9 那个
 * 「系统开箱即不可用」的故障<b>一模一样</b>，排查时极易混淆。」
 *
 * <p>{@code system:file:upload} / {@code :query} / {@code :download} 三个 {@code perms}
 * <b>仍然存在</b>（契约 §10 附表 A 有登记），但<b>只用于管理端 A22 页面的菜单显隐</b>，
 * 不作为接口鉴权依据。<b>不要"顺手补上"注解</b>：补了之后学生上传作答附件会 403，
 * 而 {@code student} 角色按 F-1 定案② 不绑任何菜单行、拿不到任何 {@code perms}。
 *
 * <p>拦人靠什么，见 {@link FileService} 类注释的四道闸与
 * {@code FileService#resolveForDownload} 的完整判定顺序表。
 *
 * <h2>三个接口的 {@code @OperLog}</h2>
 * <p>只有上传标了 —— 它是写操作。§7.2 / §7.3 是读，标了会让 {@code sys_oper_log}
 * 被下载行为淹没（一次课程页加载可能触发十几次），而这张表要给机构管理员在页面上看。
 * {@code saveParams = false}：请求参数里是 {@link MultipartFile}，
 * 虽然切面已按类型跳过二进制，但整段请求本就没有审计价值。
 */
@RestController
@RequestMapping("/api/v1/system/files")
public class SysFileController {

    private final FileService fileService;

    public SysFileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * §7.1 上传文件。类型不支持或大小超限 → {@code 10011}；频次超限 → HTTP 429（D-5）。
     *
     * <p>响应里的 {@code fileUrl} <b>恒为 {@code null}</b>（§7.1 响应字段说明），
     * 由 {@link FileUploadVO} 在类型上保证。
     */
    @PostMapping
    @OperLog(module = "文件管理", action = "上传文件", saveParams = false)
    public R<FileUploadVO> upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(value = "bizType", required = false) String bizType) {
        return R.ok(FileUploadVO.of(fileService.upload(file, bizType)));
    }

    /**
     * §7.2 查询文件详情。归属校验<b>与 §7.3 完全一致，不得放宽</b>；不通过一律 404。
     *
     * <p>{@code fileUrl} 恒为 {@code null}（{@link FileDetailVO} 在类型上保证）。
     */
    @GetMapping("/{id}")
    public R<FileDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(fileService.detail(id));
    }

    /**
     * §7.3 下载文件 —— <b>归属校验的唯一入口</b>。
     *
     * <p>响应<b>不使用通用 JSON 结构</b>（§7.3 逐字）：
     * <ul>
     *   <li>{@code storage=1}（本地）：直接返回文件流 +
     *       {@code Content-Disposition: attachment; filename*=UTF-8''…} +
     *       {@code X-Content-Type-Options: nosniff}；</li>
     *   <li>{@code storage=2}（OSS）：<b>302 重定向</b>至带签名的临时地址（30 分钟）。</li>
     * </ul>
     *
     * <h2>⚠ D-4：302 之后我们的响应头一个都不生效</h2>
     * <p>{@code 00-通用约定} §7.4 要求「下载统一 {@code Content-Disposition: attachment}
     * + {@code X-Content-Type-Options: nosniff}」，而 302 之后浏览器请求的是 OSS ——
     * <b>本方法设的头对那次请求毫无作用</b>，且 {@code sys_file.storage} 的
     * DDL 默认值就是 2，也就是说这条基线<b>在生产上从来不生效</b>。
     *
     * <p>处置（需方已知悉并接受，D-4 选项 i）：把
     * {@code response-content-disposition=attachment} 与
     * {@code response-content-type=application/octet-stream} 作为<b>参与签名</b>的
     * query 参数附在签名地址上（客户端改一个字签名就失效），
     * 见 {@code OssClient#presignedUrl}。{@code nosniff} 在 302 路径上确实拿不到 ——
     * OSS 不支持通过签名参数或对象元数据下发这个响应头。
     * {@code octet-stream + attachment} 这对组合是它缺席时的主力。
     *
     * <p>本地路径（{@code storage=1}）三个头都齐，{@code SysFileDownloadIT} 分别断言两条路径。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable("id") Long id) {
        SysFile file = fileService.resolveForDownload(id);

        Optional<String> signed = fileService.signedDownloadUrl(file);
        if (signed.isPresent()) {
            return ResponseEntity.status(302)
                    .header(HttpHeaders.LOCATION, signed.get())
                    .build();
        }

        InputStream stream = fileService.openStream(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.getFileName()))
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.getFileSize() == null ? -1L : file.getFileSize())
                .body(new InputStreamResource(stream));
    }

    /** RFC 5987，与 03-01 §7.3 的响应头示例同格式（{@code filename*=UTF-8''%E5%AD%A6…}）。 */
    private static String contentDisposition(String fileName) {
        String safe = fileName == null || fileName.isBlank() ? "download" : fileName;
        return "attachment; filename*=UTF-8''"
                + URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
