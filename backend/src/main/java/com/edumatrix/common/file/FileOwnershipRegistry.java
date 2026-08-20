package com.edumatrix.common.file;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link FileOwnershipChecker} 的注册表。<b>没有 checker 时一律拒绝（fail closed）。</b>
 *
 * <h2>为什么默认必须是 DENY —— 这是本类存在的全部理由</h2>
 * <p>需要 checker 的 bizType（见 {@link FileBizType#requiresOwnershipChecker()}）
 * 对应的领域代码<b>都还没写</b>（模块 11 / 15 / 17）。两种默认值的后果：
 * <table border="1">
 *   <caption>默认放行 vs 默认拒绝</caption>
 *   <tr><th></th><th>模块 11/15/17 落地前</th><th>落地时漏注册一个 checker</th></tr>
 *   <tr><td><b>默认放行</b></td><td>看似正常</td>
 *       <td><b>含明文初始密码的 {@code credential_sheet} 对全租户敞开</b>；
 *           接口 200、文件下得到、没有任何东西报错 —— 本项目定义的头号故障形态</td></tr>
 *   <tr><td><b>默认拒绝</b></td>
 *       <td>这些 bizType 的文件<b>根本还不会被创建</b>（03-01 §7.1 逐字：
 *           {@code fail_report}/{@code credential_sheet}/{@code export_report}
 *           「由服务端异步任务生成登记，不经本接口上传」），<b>不误伤任何人</b></td>
 *       <td>那个 bizType 的文件下不下来 —— 响亮、当场、可定位</td></tr>
 * </table>
 * <p>「暂时下不了」优于「能下且不该下」。
 *
 * <h2>当前<b>一律 404</b> 的 bizType 清单（每落地一个模块就少一条；模块 11 已划掉一条）</h2>
 * <ul>
 *   <li>{@code import_excel} / {@code fail_report} / {@code credential_sheet}
 *       —— 待<b>模块 17</b> 注册（读 {@code org_import_task.create_by}）；</li>
 *   <li>{@code export_report} —— 待<b>模块 17</b> 注册（读 {@code stat_export_task.applicant_id}）。
 *       注意 03-05 §4.8 的 {@code downloadUrl} 是那个接口自己签发的、
 *       走它自己的 {@code 40003} 校验，不受本注册表影响；</li>
 *   <li>{@code answer} —— 待<b>模块 15</b> 注册（学生本人 / 作业创建人叠加子树 / org_admin）；</li>
 * </ul>
 *
 * <h2>已解除的（模块 11）</h2>
 * <ul>
 *   <li>{@code material_attach} —— <b>已由模块 11 注册</b>：
 *       {@code course/catalog/MaterialAttachOwnershipChecker}（F-91：判定要读
 *       {@code crs_material} / {@code crs_lesson}，而检查③ 禁止 {@code org} 域 import
 *       {@code course} 域，故实现落在拥有那两张表的领域）。
 *       两支判定取自 {@code crs_material.owner_node_id} 的 DDL 列注释：
 *       <b>管理端</b>按该列做子树过滤、<b>使用端</b>走所属课时 → 课程 → 课程授权
 *       （{@code ResourceOwnerChecker.canUse}，与 03-03 §6.3 的 {@code 20013} 判定逐条相同）。
 *       <b>查不到归属仍然拒</b>（孤儿附件）—— 放行才是危险的那一侧。
 *       <p><b>解除的行为判据在 {@code org/grant/MaterialAttachDownloadIT}</b>：
 *       未授权学生 404、已授权学生拿得到文件。<b>两侧都要断言</b> ——
 *       只断言未授权那一侧的话，把 {@code canAccess} 写死 {@code false} 也全绿，
 *       而那等于这次解除什么都没解除。</li>
 * </ul>
 *
 * <h2>启动时把清单打出来</h2>
 * <p>不打的话，「哪些 bizType 现在下不了」这件事只存在于本段注释里，
 * 而运维遇到 404 时第一反应是查权限、查租户，不会想到这里。
 */
@Component
public class FileOwnershipRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileOwnershipRegistry.class);

    private final Map<FileBizType, FileOwnershipChecker> checkers = new EnumMap<>(FileBizType.class);

    public FileOwnershipRegistry(List<FileOwnershipChecker> registered) {
        for (FileOwnershipChecker checker : registered) {
            for (FileBizType bizType : checker.supportedBizTypes()) {
                FileOwnershipChecker previous = checkers.put(bizType, checker);
                if (previous != null) {
                    // 两个 checker 抢同一个 bizType：谁生效取决于 Bean 顺序，
                    // 而那是不确定的 —— 让它响亮失败，别留一个"看运气的鉴权"
                    throw new IllegalStateException(
                            "bizType " + bizType.code() + " 注册了两个 FileOwnershipChecker："
                                    + previous.getClass().getName() + " 与 " + checker.getClass().getName()
                                    + "。同一个 bizType 只能有一个归属判定实现。");
                }
            }
        }
        logPendingBizTypes();
    }

    /**
     * 归属校验。<b>需要 checker 而没有注册 → 拒绝</b>。
     *
     * <p>不需要 checker 的 bizType（{@code course_cover} / {@code material_image} /
     * {@code avatar} / {@code common}）直接放行 —— 它们的归属校验就是"本租户已登录"，
     * 而那一道已经在 {@code FileService} 里由租户插件过掉了（03-01 §7.3
     * 「其余 bizType 本租户已登录用户可下载」）。
     */
    public boolean canAccess(FileOwnershipChecker.FileRef file, Long userId, boolean isOrgAdmin) {
        if (!file.bizType().requiresOwnershipChecker()) {
            return true;
        }
        FileOwnershipChecker checker = checkers.get(file.bizType());
        if (checker == null) {
            log.warn("bizType={} 尚无 FileOwnershipChecker，按 fail-closed 拒绝访问 fileId={}。"
                            + "这是设计行为不是故障：对应模块（11/15/17）注册 checker 后自动解除。",
                    file.bizType().code(), file.fileId());
            return false;
        }
        return checker.canAccess(file, userId, isOrgAdmin);
    }

    /** 供测试与运维自查：当前哪些 bizType 处于 fail-closed 状态。 */
    public Set<FileBizType> pendingBizTypes() {
        Set<FileBizType> pending = java.util.EnumSet.noneOf(FileBizType.class);
        for (FileBizType bizType : FileBizType.requiringChecker()) {
            if (!checkers.containsKey(bizType)) {
                pending.add(bizType);
            }
        }
        return pending;
    }

    private void logPendingBizTypes() {
        Set<FileBizType> pending = pendingBizTypes();
        if (pending.isEmpty()) {
            log.info("文件归属校验：全部需要 checker 的 bizType 均已注册");
            return;
        }
        Set<String> codes = new TreeSet<>();
        pending.forEach(t -> codes.add(t.code()));
        log.warn("文件归属校验 fail-closed 清单（下载一律 404，待对应模块注册 checker）：{}", codes);
    }
}
