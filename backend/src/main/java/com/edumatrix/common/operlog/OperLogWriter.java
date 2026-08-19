package com.edumatrix.common.operlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.edumatrix.common.id.IdWorker;
import com.edumatrix.common.operlog.mapper.OperLogMapper;
import com.edumatrix.common.tenant.TenantHelper;

/**
 * {@code sys_oper_log} 的<b>唯一写入实现</b>（F-25 的收敛点）。
 *
 * <p>两个调用方、一份实现：
 * <pre>
 * OperLogAspect ───────┐
 *                      ├──→ OperLogWriter ──→ OperLogMapper ──→ sys_oper_log
 * MemberOperLogWriter ─┘     （截断 / tenant / status 口径都只有这一份）
 * </pre>
 *
 * <h2>无会话时的 {@code tenant_id} / {@code user_id} —— F-25 悬着的那一条，在此定案</h2>
 * <p>F-25 逐字：「契约 §2.8 规则 3 的 Job 路径<b>没有会话</b>……那条路径的 {@code tenant_id}
 * 怎么给是<b>模块 05/09 的工单内容</b>，模块 06 无权替它定」。四档：
 * <table border="1">
 *   <caption>四档取值</caption>
 *   <tr><th>上下文</th><th>{@code tenant_id}</th><th>{@code user_id}</th><th>依据</th></tr>
 *   <tr><td>普通 Web 会话</td><td>会话租户</td><td>会话用户</td><td>{@code TenantHelper} 路径④</td></tr>
 *   <tr><td>超管会话</td><td><b>0</b>（平台级，合法值）</td><td>超管 userId</td><td>契约 §2.9</td></tr>
 *   <tr><td>{@code runWithTenant} 包住的 Job / Worker / 事件消费</td>
 *       <td>显式值（路径①）</td><td><b>{@code null}</b></td><td>契约 §2.8 规则 1</td></tr>
 *   <tr><td>以上全落空</td><td colspan="2"><b>不写这一行 + WARN</b></td>
 *       <td>契约 §2.8 规则 3「无法确定租户的写入一律拒绝并告警，<b>绝不"猜一个"</b>」</td></tr>
 * </table>
 *
 * <p><b>{@code user_id} 在 Job 路径留 {@code null} 而不是 0</b>：0 会指向平台超管，
 * 那是一条<b>假审计记录</b>，而 {@code sys_oper_log} 恰恰是排查越权时要看的表
 * （与 {@code AuditFieldHandler} 对 {@code create_by} 的取舍同源）。
 *
 * <h2>写日志失败绝不改变业务结果</h2>
 * <p>沿用 {@code auth/service/LoginLogService#record} 的取舍，那段注释逐字：
 * 「<b>日志是证据，不是流程的一环</b>：写不进去也不能改变登录结果」。
 * 本类吞掉异常并记 ERROR。
 *
 * <p><b>但这带来一个必须承认的后果</b>：`sys_oper_log` 写失败时没有任何用户可见的信号。
 * 所以 ERROR 日志里带齐 {@code module/action/method}，且不吞
 * {@code TenantContextMissingException} 之外的堆栈。
 */
@Component
public class OperLogWriter {

    private static final Logger log = LoggerFactory.getLogger(OperLogWriter.class);

    /** 平台级 / 超管操作的租户归属（契约 §2.9，与 {@code LoginLogService} 的 0 同口径）。 */
    public static final long PLATFORM_TENANT_ID = 0L;

    /** {@code sys_oper_log.status}：0 成功（DDL 默认值）。 */
    public static final int STATUS_SUCCESS = 0;

    /** {@code sys_oper_log.status}：1 失败。<b>业务码拒绝（如 10011）也算失败</b>——那正是审计要看的。 */
    public static final int STATUS_FAIL = 1;

    /** {@code module} 列宽 {@code VARCHAR(50)}。 */
    private static final int MAX_MODULE = 50;
    /** {@code action} 列宽 {@code VARCHAR(50)}。 */
    private static final int MAX_ACTION = 50;
    /** {@code method} 列宽 {@code VARCHAR(200)}。 */
    private static final int MAX_METHOD = 200;
    /** {@code ip} 列宽 {@code VARCHAR(64)}。 */
    private static final int MAX_IP = 64;
    /** {@code error_msg} 列宽 {@code VARCHAR(2000)}。 */
    private static final int MAX_ERROR_MSG = 2000;

    /**
     * {@code params} 是 {@code TEXT}（上限 64KB），这里再收到 <b>16KB</b>。
     *
     * <p>列宽不是唯一约束：批量授权 5000 行、图文资料富文本正文进日志会让这张表
     * 以远超预期的速度增长，而它的 DDL 表注释是「可按时间归档清理」——
     * 可清理不等于可以任意大。超出部分以 {@code …[truncated]} 结尾，肉眼可辨。
     */
    private static final int MAX_PARAMS = 16 * 1024;

    private static final String TRUNCATED_SUFFIX = "…[truncated]";

    private final OperLogMapper operLogMapper;

    public OperLogWriter(OperLogMapper operLogMapper) {
        this.operLogMapper = operLogMapper;
    }

    /**
     * <b>审计路径</b>：写一行，失败只记 ERROR、不抛。{@code OperLogAspect} 用它。
     *
     * <p>租户解析不出时<b>不写</b>并记 WARN（契约 §2.8 规则 3）。
     *
     * @param userId   无操作人（Job / Worker / 事件消费）时传 {@code null}
     * @param params   已脱敏的 JSON 文本；不记参数时传 {@code null}
     * @param status   {@link #STATUS_SUCCESS} / {@link #STATUS_FAIL}
     * @param tenantId 显式租户；传 {@code null} 表示"按当前上下文解析"
     */
    public void write(Long userId, String module, String action, String method,
                      String params, String ip, int status, String errorMsg, int costMs,
                      Long tenantId) {
        try {
            doWrite(userId, module, action, method, params, ip, status, errorMsg, costMs, tenantId);
        } catch (RuntimeException e) {
            // 日志是证据，不是流程的一环（LoginLogService#record 同口径：
            // 「写不进去也不能改变登录结果」）
            log.error("写 sys_oper_log 失败 module={} action={} method={}", module, action, method, e);
        }
    }

    /**
     * <b>合规留痕路径</b>：写一行，失败<b>照抛</b>。{@code org/member/service/MemberOperLogWriter} 用它。
     *
     * <h2>为什么必须有第二个入口，而不能都用 {@link #write}</h2>
     * <p>模块 07 的两条留痕是在<b>建人事务内</b>调用的，那个类的注释逐字：
     * 「<b>在建学生的同一事务内调用</b>：留痕与建档必须<b>同生共死</b>。
     * 建档回滚了却留下一条『已取得监护人同意』，比没有这条记录更糟。」
     *
     * <p>如果这条路径也吞异常，就会出现相反方向的同一个毛病：<b>建档成功了，
     * 而那条法定证据（个保法第 31 条）静默丢失</b> —— 接口 200、学生建好了、
     * 监管问询时拿不出东西。收敛写入实现<b>不等于</b>让两条路径的失败语义也合并，
     * 那会是一次不报错的能力回退。
     */
    public void writeOrThrow(Long userId, String module, String action, String method,
                             String params, String ip, int status, String errorMsg, int costMs,
                             Long tenantId) {
        doWrite(userId, module, action, method, params, ip, status, errorMsg, costMs, tenantId);
    }

    private void doWrite(Long userId, String module, String action, String method,
                         String params, String ip, int status, String errorMsg, int costMs,
                         Long tenantId) {
        Long resolvedTenantId = tenantId != null ? tenantId : resolveTenantId();
        if (resolvedTenantId == null) {
            log.warn("放弃写 sys_oper_log：租户上下文缺失（契约 §2.8 规则 3：无法确定租户的写入一律拒绝，"
                    + "绝不猜一个）。module={} action={} method={}", module, action, method);
            return;
        }
        operLogMapper.insert(IdWorker.nextId(), userId,
                truncate(module, MAX_MODULE), truncate(action, MAX_ACTION),
                truncate(method, MAX_METHOD), truncate(params, MAX_PARAMS),
                truncate(ip, MAX_IP), status, truncate(errorMsg, MAX_ERROR_MSG),
                Math.max(costMs, 0), resolvedTenantId);
    }

    /**
     * 超管会话 → {@link #PLATFORM_TENANT_ID}；其余按 {@code TenantHelper} 四条路径解析。
     *
     * <p><b>用 {@code getTenantIdOrNull} 而不是 {@code requireTenantId}</b>：后者会抛异常，
     * 而写日志失败不该把一次成功的业务操作变成 500。解析不出时由调用方（本类的
     * {@link #write}）按"不写 + WARN"处理。
     */
    private Long resolveTenantId() {
        if (TenantHelper.isSuperAdminSession()) {
            return PLATFORM_TENANT_ID;
        }
        return TenantHelper.getTenantIdOrNull();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }
}
