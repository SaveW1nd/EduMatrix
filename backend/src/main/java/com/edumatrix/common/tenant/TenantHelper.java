package com.edumatrix.common.tenant;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租户上下文的唯一入口。
 *
 * <h2>取值优先级（四条路径 + 一条兜底，顺序不可调换）</h2>
 * <pre>
 * ① 显式 runWithTenant(id, …) 设的 ThreadLocal   ← Job / SMQ 事件消费 / 异步 Worker（契约 §2.8）
 * ② 显式 ignore(…) 包裹                          ← 登录按用户名查 sys_user 这类必须跨租户的
 * ③ 超管会话 → 整体放行                          ← 契约 §2.9，与 sys_role 的 tenant_id = 0
 *                                                   放行是两条不同通道，不得混用
 * ④ CurrentContextProvider.getTenantId()         ← 普通 Web 请求，模块 02 实现
 * ─────────────────────────────────────────────────────────────────────────────
 * 兜底：以上全落空 → 抛 TenantContextMissingException + ERROR 日志，绝不放行
 * </pre>
 *
 * <p><b>为什么①在④之前</b>：无会话入口（定时任务、事件消费、Worker）跑在线程池上，线程会被复用。
 * 若先读会话，一个残留的会话上下文就会让任务写进错误的租户 —— 而这是不报错的。
 * 显式设置必须压过一切隐式来源。
 *
 * <h2>{@code clear()} 不是可选项</h2>
 * <p>契约 §2.8 规则 1 的原话：「处理完成后必须 {@code clear()} —— <b>线程池会复用线程，
 * 不清理就是下一个任务串租户</b>」。所以正确写法永远是 {@link #runWithTenant}，
 * 它用 {@code try/finally} 保证 {@code clear()} 一定执行，并且<b>恢复而非清空</b>外层的值
 * （嵌套调用时不会把外层上下文抹掉）。
 * {@link #setTenantId(Long)} / {@link #clear()} 只为无法用闭包表达的场景保留。
 *
 * <h2>{@code ignore()} 是逃生舱，必须显式、可 grep、可审计</h2>
 * <p>它关掉的是整条 SQL 的租户过滤，威力与业务代码里手写 {@code OR tenant_id = 0} 相同，
 * 因此纪律也相同：<b>新增一处 {@code ignore()} 调用点，必须能说清"为什么这个查询非跨租户不可"。</b>
 * 现有的正当理由只有一类 —— <b>登录时按用户名查 {@code sys_user}</b>：那一刻还不知道租户是谁，
 * 租户恰恰是这次查询的结果。除此之外，先怀疑是自己漏了 {@link #runWithTenant}。
 *
 * <p>审计命令：
 * <pre>grep -rn "TenantHelper.ignore" backend/src/main/java</pre>
 *
 * <h2>不要在这里解决 F-14</h2>
 * <p>{@code GET /api/v1/vod/decrypt-key} 是一条<b>无会话的读</b>（身份来自 URL 上的
 * {@code MtsHlsUriToken}），四条路径都套不进去，因此成了待决项 F-14（04-实施计划.md §E，卡模块 12）。
 * 本类不预设它的解法：两条候选路径（从 token 反查 studentId 再 {@code runWithTenant} 包住整条校验链 /
 * 令牌里直接带 tenantId）<b>都能落在路径①上</b>，所以 {@link #runWithTenant} 没有写成"只给 Job 用"。
 * 定案只在 F-14 一处发生。
 */
public final class TenantHelper {

    private static final Logger log = LoggerFactory.getLogger(TenantHelper.class);

    /** 路径①：显式设置的租户上下文。 */
    private static final ThreadLocal<Long> EXPLICIT_TENANT_ID = new ThreadLocal<>();

    /** 路径②：忽略租户过滤的嵌套深度。用计数而非布尔，嵌套 ignore 才不会被内层提前关掉。 */
    private static final ThreadLocal<Integer> IGNORE_DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 路径③④：会话来源。启动时由 {@code TenantConfig} 注入，永远非 null。 */
    private static volatile CurrentContextProvider provider = new NoSessionCurrentContextProvider();

    private TenantHelper() {
    }

    /**
     * 由 Spring 装配时调用一次。业务代码不要碰它。
     *
     * <p>用静态字段而不是注入，是因为 {@link TenantHelper} 按 05-工程结构.md §E 的签名是
     * 静态工具（{@code TenantHelper.setTenantId / clear / runWithTenant}），
     * 而它要被无 Spring 上下文的地方（如 MyBatis 拦截器内部）直接调用。
     */
    public static void setProvider(CurrentContextProvider newProvider) {
        provider = newProvider == null ? new NoSessionCurrentContextProvider() : newProvider;
    }

    // =====================================================================
    // 路径①：显式租户上下文
    // =====================================================================

    /**
     * 显式设置当前线程的租户上下文。
     *
     * <p><b>优先用 {@link #runWithTenant}</b>。直接用本方法就必须自己保证 {@link #clear()}
     * 在 {@code finally} 里执行 —— 漏一次，线程池复用时下一个任务就串租户。
     */
    public static void setTenantId(Long tenantId) {
        if (tenantId == null) {
            EXPLICIT_TENANT_ID.remove();
        } else {
            EXPLICIT_TENANT_ID.set(tenantId);
        }
    }

    /** 清除显式租户上下文。与 {@link #setTenantId(Long)} 成对，缺一即串租户。 */
    public static void clear() {
        EXPLICIT_TENANT_ID.remove();
    }

    /** 当前显式设置的租户 ID；未设置时返回 {@code null}。 */
    public static Long getExplicitTenantId() {
        return EXPLICIT_TENANT_ID.get();
    }

    /**
     * 在指定租户上下文中执行，<b>结束后一定恢复原上下文</b>（契约 §2.8 规则 1）。
     *
     * <p>三类无会话入口（事件消费 / 定时任务 / 异步 Worker）一律用它包住：
     * <pre>
     * TenantHelper.runWithTenant(video.getTenantId(), () -&gt; vodService.onTranscodeComplete(evt));
     * </pre>
     *
     * <p>跨租户的任务<b>必须按租户分片</b>，每片各包一次（契约 §2.8 规则 2）——
     * 禁止"一个事务里跨租户批量写"，那样任何一行的租户判定错误都会被整批放大。
     */
    public static void runWithTenant(Long tenantId, Runnable action) {
        runWithTenant(tenantId, () -> {
            action.run();
            return null;
        });
    }

    /** 同 {@link #runWithTenant(Long, Runnable)}，带返回值。 */
    public static <T> T runWithTenant(Long tenantId, Supplier<T> action) {
        if (tenantId == null) {
            throw new TenantContextMissingException(
                    "runWithTenant 的 tenantId 为 null。契约 §2.8 规则 3：无法确定租户的写入一律拒绝并告警，"
                            + "绝不猜一个或退化为忽略租户条件。请检查数据来源行是否真的取到了 tenant_id");
        }
        Long previous = EXPLICIT_TENANT_ID.get();
        EXPLICIT_TENANT_ID.set(tenantId);
        try {
            return action.get();
        } finally {
            // 恢复而非清空：嵌套调用时不能把外层的上下文抹掉
            if (previous == null) {
                EXPLICIT_TENANT_ID.remove();
            } else {
                EXPLICIT_TENANT_ID.set(previous);
            }
        }
    }

    // =====================================================================
    // 路径②：显式忽略租户过滤（逃生舱）
    // =====================================================================

    /**
     * 在忽略租户过滤的上下文中执行。<b>每一处调用都要在注释里说清为什么非跨租户不可。</b>
     *
     * <p>唯一已知的正当场景：登录时按用户名查 {@code sys_user} —— 那一刻还不知道租户是谁，
     * 租户是这次查询的<b>结果</b>而不是<b>前提</b>。
     */
    public static void ignore(Runnable action) {
        ignore(() -> {
            action.run();
            return null;
        });
    }

    /** 同 {@link #ignore(Runnable)}，带返回值。 */
    public static <T> T ignore(Supplier<T> action) {
        IGNORE_DEPTH.set(IGNORE_DEPTH.get() + 1);
        try {
            return action.get();
        } finally {
            int depth = IGNORE_DEPTH.get() - 1;
            if (depth <= 0) {
                IGNORE_DEPTH.remove();
            } else {
                IGNORE_DEPTH.set(depth);
            }
        }
    }

    /** 当前是否处于 {@link #ignore} 上下文中。供租户插件判断，业务代码一般不用。 */
    public static boolean isIgnored() {
        return IGNORE_DEPTH.get() > 0;
    }

    // =====================================================================
    // 路径③④ + 兜底
    // =====================================================================

    /**
     * 当前会话是否为平台超管。超管的跨租户访问靠<b>租户插件整体放行</b>（契约 §2.9），
     * 与 {@code sys_role} / {@code sys_role_menu} 的 {@code OR tenant_id = 0} 放行
     * 是两条不同通道，<b>不得混用</b>。
     *
     * <p>注意顺序：显式租户上下文（路径①）优先于超管身份。超管手动指定要操作哪个租户时
     * （如开通机构后往里写数据），应当按那个租户过滤，而不是继续全局放行。
     */
    public static boolean isSuperAdminSession() {
        return EXPLICIT_TENANT_ID.get() == null && provider.isSuperAdmin();
    }

    /** 当前登录用户 ID；无会话时 {@code null}。 */
    public static Long getUserId() {
        return provider.getUserId();
    }

    /**
     * 按四条路径解析当前租户 ID；解析不出时返回 {@code null}（<b>不抛异常</b>）。
     *
     * <p>只在「拿不到也有合法后续动作」的地方用，例如租户插件先判 {@link #isIgnored()} /
     * {@link #isSuperAdminSession()} 再决定是否需要租户值。<b>要拿来拼 SQL 条件时一律用
     * {@link #requireTenantId()}</b>。
     */
    public static Long getTenantIdOrNull() {
        Long explicit = EXPLICIT_TENANT_ID.get();
        if (explicit != null) {
            return explicit;
        }
        return provider.getTenantId();
    }

    /**
     * 按四条路径解析当前租户 ID，<b>解析不出即抛异常并记 ERROR</b>。
     *
     * <p>这是契约 §2.8 规则 3 的落点：「无法确定租户的写入一律拒绝并告警，
     * 绝不'猜一个'或退化为忽略租户条件」。租户插件拼过滤条件时走的就是这一条 ——
     * 拿不到租户就让这条 SQL 失败，而不是让它变成一条无租户条件的全库查询。
     */
    public static Long requireTenantId() {
        Long tenantId = getTenantIdOrNull();
        if (tenantId == null) {
            log.error("租户上下文缺失：runWithTenant / ignore / 超管会话 / 会话租户 四条路径全部落空。"
                    + "按契约 §2.8 规则 3 拒绝本次操作，不退化为忽略租户条件");
            throw new TenantContextMissingException(
                    "无法确定当前租户。三类无会话入口（事件消费 / 定时任务 / 异步 Worker）"
                            + "必须用 TenantHelper#runWithTenant 包住；确需跨租户的查询"
                            + "必须用 TenantHelper#ignore 显式声明（契约 §2.8 规则 1/3）");
        }
        return tenantId;
    }

    /**
     * 仅供测试与容器关闭时使用：把三处 ThreadLocal 全部复位。
     *
     * <p>不要在业务代码里调它 —— 它会连同外层的上下文一起清掉。
     */
    public static void reset() {
        EXPLICIT_TENANT_ID.remove();
        IGNORE_DEPTH.remove();
    }
}
