package com.edumatrix.common.file;

import java.util.Set;

/**
 * 按 {@code bizType} 的<b>文件归属校验</b>能力。SPI：本接口在 {@code common/}，
 * 各实现由<b>拥有那张业务表的领域</b>注册。
 *
 * <h2>为什么必须是 SPI（不是 {@code system/file} 自己查）</h2>
 * <p>03-01 §7.3 的归属校验要读的三张表全在别的领域，而
 * {@code check_backend_conventions.sh} 检查③ 禁止 {@code system} 域
 * {@code import com.edumatrix.org.* / .stat.* / .homework.*}：
 * <table border="1">
 *   <caption>谁来注册</caption>
 *   <tr><th>bizType</th><th>要读的表</th><th>域</th><th>交付模块</th></tr>
 *   <tr><td>{@code import_excel} / {@code fail_report} / {@code credential_sheet}</td>
 *       <td>{@code org_import_task}</td><td>{@code org}</td><td>17</td></tr>
 *   <tr><td>{@code export_report}</td><td>{@code stat_export_task}</td><td>{@code stat}</td><td>17</td></tr>
 *   <tr><td>{@code answer}</td><td>{@code hw_answer_sheet} 等</td><td>{@code homework}</td><td>15</td></tr>
 *   <tr><td>{@code material_attach}</td><td>{@code crs_material} → {@code crs_lesson} → {@code org_resource_grant}</td>
 *       <td><b>{@code course}</b>（F-91：判定要读 crs_ 两张表，而检查③ 禁止 org 域 import course 域）</td><td><b>11 · 已注册</b></td></tr>
 * </table>
 * <p>模块 05 交付时四张表<b>都还没有对应的代码</b>（{@code material_attach} 那一行已由模块 11 补上）。所以模块 05 只能交付 SPI + 注册表，
 * 而注册表在没有实现时的行为<b>必须是拒绝</b>（见 {@link FileOwnershipRegistry}）。
 *
 * <h2>⚠ {@code material_attach} 为什么在这张表里（B-3 定案）</h2>
 * <p>03-01 §7.3 原文把它归在「其余 bizType <b>本租户已登录用户可下载</b>」，
 * 而 03-03 §6.3 要求学生看图文课时必须「该学生节点被显式授权该课程」，
 * 否则 {@code 20013}。<b>同一份讲义，走课时接口要授权，走文件接口不要</b>；
 * 而 {@code fileId} 是雪花 ID、同租户内时间相邻可近邻枚举
 * （03-01 §7.2 自己就用这一点论证过为什么详情接口不能下发直链）。
 *
 * <p>它现在没爆，只因为模块 08 还没建、库里没有 {@code material_attach} 行。
 * <b>模块 05 一上线这条路径就是通的，只等模块 08 往里放内容。</b>
 * 这是与 03-01 §7.3 原文的一处<b>有意分叉</b>，已登记 F-38。
 *
 * <h2>实现方要注意的两件事</h2>
 * <ol>
 *   <li><b>返回 {@code false} 一律映射为 404，不是 403</b>（03-01 §7.3「不暴露存在性」、
 *       {@code 00-通用约定} §2.4 越界三分法「路径上的资源越界 → 404」）。
 *       映射由 {@code FileService} 统一做，实现方只答"能不能"；</li>
 *   <li><b>不要在实现里再判租户</b>：租户闸在 {@code FileService} 里由租户插件先过一道，
 *       实现方只判"这个人是不是这份文件的归属人"。多判一次不会更安全，
 *       但会让"到底哪一层拒的"变成两处答案。</li>
 * </ol>
 */
public interface FileOwnershipChecker {

    /** 本实现负责哪些 bizType。同一个 bizType <b>只能有一个</b> checker（注册表会拒绝重复）。 */
    Set<FileBizType> supportedBizTypes();

    /**
     * 当前调用者能否访问这份文件。
     *
     * @param file        文件的最小事实（不传实体，避免 {@code common} 认识 {@code system} 的实体）
     * @param userId      当前登录用户；无会话时不会走到这里
     * @param isOrgAdmin  是否 {@code org_admin}（§7.3 里三种 bizType 对它有特别放行）
     */
    boolean canAccess(FileRef file, Long userId, boolean isOrgAdmin);

    /**
     * 归属判定需要的全部输入。
     *
     * @param fileId   {@code sys_file.id}
     * @param bizType  已解析的业务类型
     * @param createBy {@code sys_file.create_by} —— 上传者。<b>不能只靠它</b>：
     *                 {@code fail_report} / {@code credential_sheet} / {@code export_report}
     *                 是服务端任务生成的，{@code create_by} 可能为 {@code null}，
     *                 真正的归属人在 {@code org_import_task.create_by} /
     *                 {@code stat_export_task.applicant_id} 上
     * @param tenantId {@code sys_file.tenant_id}
     */
    record FileRef(Long fileId, FileBizType bizType, Long createBy, Long tenantId) {
    }
}
