package com.edumatrix.common.file;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 对象键（{@code sys_file.file_url} 存的就是它）的<b>唯一</b>生成处。
 *
 * <h2>键的形状：{@code {bizType}/{yyyy/MM/dd}/{fileId}.{规范扩展名}}</h2>
 * <ul>
 *   <li><b>{@code {fileId}.{规范扩展名}} 是 {@code 00-通用约定} §7.4 的硬要求</b>：
 *       「服务端<b>强制重命名</b>为 {@code {fileId}.{规范扩展名}}」。原始文件名只进
 *       {@code sys_file.file_name} 供展示与下载时回填，<b>绝不进路径</b> ——
 *       否则 {@code ../../etc/passwd} 这类名字会跟着进对象键；</li>
 *   <li><b>规范扩展名来自魔数判定结果，不是用户给的那个</b>（{@code FileTypeDetector}）。
 *       上传 {@code payload.docx}（实为 xlsx）时这里已经被拒了，走不到本类；</li>
 *   <li><b>{@code {bizType}} 前缀</b>让 {@code TempFileCleanupJob} 的清理与
 *       RAM 策略的将来收窄都能按前缀做；</li>
 *   <li><b>日期分片</b>只为运维时肉眼可读，不参与任何判定。</li>
 * </ul>
 *
 * <p><b>键里不含 {@code tenantId}</b>：租户隔离由 {@code sys_file.tenant_id} 与租户插件保证，
 * 靠对象键做隔离等于把安全边界搬到一个可被拼接的字符串上。
 */
public final class FileKeys {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private FileKeys() {
    }

    /**
     * @param bizType         已通过白名单校验的业务类型
     * @param fileId          雪花 ID（{@code sys_file.id}）
     * @param canonicalExt    <b>魔数判定出的</b>规范扩展名，小写、不带点
     */
    public static String build(FileBizType bizType, long fileId, String canonicalExt) {
        return bizType.code() + "/" + LocalDate.now().format(DATE_PATH) + "/" + fileId + "." + canonicalExt;
    }
}
