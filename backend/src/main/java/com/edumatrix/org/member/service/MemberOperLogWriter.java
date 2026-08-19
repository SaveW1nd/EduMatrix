package com.edumatrix.org.member.service;

import org.springframework.stereotype.Service;

import com.edumatrix.common.operlog.OperLogWriter;
import com.edumatrix.common.tenant.TenantHelper;

/**
 * 往 {@code sys_oper_log} 写<b>合规留痕</b>行。模块 07 只写两处，其余一律靠 {@code @OperLog} 注解。
 *
 * <h2>为什么模块 07 显式写，而模块 06 只标注解</h2>
 * <p>差别<b>不是</b>「注解切面覆盖不到定时任务」——{@code @OperLog} 是
 * {@code @Target(ElementType.METHOD)}，Spring AOP 也切得到任何 Bean 方法，那条论据可证伪。
 * 真正的两条理由是：
 * <ol>
 *   <li><b>工单授权不同。</b>04-实施计划.md 模块 07 的「涉及表」<b>写</b>栏明列
 *       {@code sys_oper_log}；模块 06 的没有。模块 06 当时的原话正是「那条路径的
 *       {@code tenant_id} 怎么给是模块 05/09 的工单内容，<b>模块 06 无权替它定</b>」——
 *       模块 07 有这个授权；
 *   <li><b>Job 路径没有会话。</b>脱敏任务跑在 XXL-Job 里，
 *       {@code TenantHelper.getUserId()} 与会话租户都取不到值。切面无论落在哪个模块，
 *       都得有人先定「无会话时 {@code tenant_id} / {@code user_id} 怎么给」——
 *       那正是 F-25 登记的原话。模块 07 就本模块的这一条给出答案：
 *       {@code tenant_id} 由调用方用 {@code TenantHelper.runWithTenant} 从<b>被处理的数据行</b>
 *       显式提供（契约 §2.8 规则 1），{@code user_id} 留 {@code null}（没有操作人，
 *       填 0 会是一条假审计记录 —— 与 {@code AuditFieldHandler} 对 {@code create_by} 的取舍同源）。
 * </ol>
 *
 * <h2>与模块 05 的切面<b>语义不重复</b>，因此模块 05 落地时本类的调用点一处未删</h2>
 * <p>两者的 {@code action} 不同、语义也不同：
 * <table border="1">
 *   <caption>两行的分工</caption>
 *   <tr><th></th><th>写的人</th><th>{@code action}</th><th>记的是什么</th></tr>
 *   <tr><td>操作日志</td><td>模块 05 的 {@code OperLogAspect}</td><td>{@code 创建学生}</td>
 *       <td>「谁在什么时候调了创建学生这个接口」</td></tr>
 *   <tr><td>合规留痕</td><td>本类</td><td>{@code 监护人同意留痕}</td>
 *       <td>「机构已确认取得监护人同意」这个<b>法定事实</b>（个保法第 31 条）</td></tr>
 * </table>
 * <p>前者是运维审计，后者是<b>监管问询时要拿出来的证据</b>。合并成一行的后果是：
 * 将来任何一次「操作日志按时间归档清理」都会把合规证据一起清掉，
 * 而 {@code sys_oper_log} 的 DDL 表注释逐字就是「可按时间归档清理」。
 *
 * <p><b>订正一处事实错误</b>：本段原写「切面将来写的 {@code action} 是 {@code 新增}」，
 * 而 {@code OrgStudentController} 上的注解逐字是 {@code action = "创建学生"}。
 * 方向不受影响（两者仍不同），但那句话本身与它自己模块的代码对不上，已改。
 *
 * <h2>⚠ 模块 05 收敛了<b>写入实现</b>（F-25）：本类不再自带 Mapper</h2>
 * <p>{@code org/member/mapper/MemberOperLogMapper} <b>已删除</b>，本类改调
 * {@code common/operlog/OperLogWriter} —— 与 {@code OperLogAspect} 共用同一份
 * 截断规则、脱敏规则与 {@code tenant_id} 取值口径。
 *
 * <p><b>为什么原论证不足以让两个 Mapper 共存</b>：它论证的是「两<b>行数据</b>语义不同」，
 * 这一点成立且已保留；但需方担心的是「两<b>条代码路径</b>各带一套规则」——
 * 那是本项目反复出事的形态，且当场就能收敛，所以收敛了。
 * 收敛之后不存在「改一份必须改另一份」，故本类<b>不需要</b>那种同步警告。
 *
 * <p><b>用 {@code writeOrThrow} 而不是 {@code write}</b>：见下方 {@link #guardianConsent}
 * 对「同生共死」的要求 —— 收敛写入实现<b>不等于</b>把失败语义也合并。
 *
 * <h2>不做的事</h2>
 * <ul>
 *   <li><b>不写 {@code params}</b>：请求体里有 {@code guardianPhone} / {@code phone}
 *       与 {@code initPassword} 明文。前两个是契约 §7.2 要脱敏的敏感个人信息，
 *       第三个是 PRD §7.3「明文永不落库」。脱敏白/黑名单是模块 05 工单的一整块，
 *       本类<b>一个字都不写进 {@code params}</b>；
 *   <li><b>不碰 {@code cost_ms} / {@code status} / {@code error_msg}</b>：那是切面的职责，
 *       本类只在业务成功路径上被调用，{@code status} 走 DDL 默认值 0。
 * </ul>
 */
@Service
public class MemberOperLogWriter {

    /** {@code sys_oper_log.module}，与菜单「学员管理」同名（DDL 注释：「如 学生管理/作业管理」）。 */
    public static final String MODULE_STUDENT = "学生管理";

    /** 规则 9：F7-1 监护人同意留痕。 */
    public static final String ACTION_GUARDIAN_CONSENT = "监护人同意留痕";

    /** 规则 10：F7-3 删除请求脱敏。 */
    public static final String ACTION_ANONYMIZE = "删除请求脱敏";

    private final OperLogWriter operLogWriter;

    public MemberOperLogWriter(OperLogWriter operLogWriter) {
        this.operLogWriter = operLogWriter;
    }

    /**
     * 规则 9 / PRD F7-1：记录「机构已确认取得监护人同意」的<b>操作人与时间戳</b>。
     *
     * <p>03-02 §6.2 的 {@code guardianConsent} 参数说明逐字：「服务端据此写
     * {@code sys_oper_log} 留痕（操作人 + 时间戳）——机构线下签署的知情同意书由机构自行留存，
     * 系统侧只承担留痕责任」。{@code oper_time} 由 DDL 的
     * {@code DEFAULT CURRENT_TIMESTAMP} 赋值 —— <b>只认数据库这一个时钟</b>。
     *
     * <p><b>在建学生的同一事务内调用</b>：留痕与建档必须同生共死。
     * 建档回滚了却留下一条「已取得监护人同意」，比没有这条记录更糟。
     */
    public void guardianConsent(Long studentUserId, Long tenantId) {
        // writeOrThrow：留痕写不进去必须让建人事务一起回滚（见方法注释「同生共死」）
        operLogWriter.writeOrThrow(TenantHelper.getUserId(),
                MODULE_STUDENT, ACTION_GUARDIAN_CONSENT,
                "POST /api/v1/org/students#" + studentUserId,
                null, null, OperLogWriter.STATUS_SUCCESS, null, 0, tenantId);
    }

    /**
     * 规则 10 / PRD F7-3：脱敏任务每处理一名学员记一行。
     *
     * <p><b>{@code user_id} 为 {@code null}</b>：Job 没有操作人。填 0 会指向平台超管，
     * 那是一条假审计记录，而 {@code sys_oper_log} 恰恰是排查越权时要看的表。
     *
     * <p><b>{@code tenantId} 必须由调用方从数据行显式取</b>（契约 §2.8 规则 1）——
     * Job 里没有会话租户，靠线程残留会把日志写到上一个租户名下。
     */
    public void anonymized(Long studentId, Long tenantId) {
        // 同样 writeOrThrow：脱敏与留痕在 StudentAnonymizeService#anonymize 的同一事务内，
        // 留痕丢了而脱敏落了库，等于把一次不可逆操作的唯一记录抹掉
        operLogWriter.writeOrThrow(null,
                MODULE_STUDENT, ACTION_ANONYMIZE,
                "AnonymizeArchivedStudentJob#" + studentId,
                null, null, OperLogWriter.STATUS_SUCCESS, null, 0, tenantId);
    }
}
