package com.edumatrix.org.grant.job;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.edumatrix.common.metrics.MetricsRegistry;
import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.grant.mapper.GrantHealthMapper;
import com.edumatrix.org.grant.mapper.GrantTenantMapper;
import com.edumatrix.org.grant.service.GrantHealthService;
import com.xxl.job.core.handler.annotation.XxlJob;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 悬挂授权巡检（PRD FR-7、契约 §2.5 规则 6、§7.1 两项指标）。
 *
 * <h2>⚠ 两条触发路径都必须登记，只登记一边 = 切换那天任务静默消失</h2>
 * <table border="1">
 *   <caption>任何时刻只有一条生效（F-41：调度中心暂不部署）</caption>
 *   <tr><th>{@code xxl.job.enabled}</th><th>触发者</th><th>登记在哪</th></tr>
 *   <tr><td>{@code false} 或未配（<b>现状</b>）</td><td>Spring 调度</td>
 *       <td>{@code job/ScheduledJobTrigger} 的 {@code CRON_GRANT_CONSISTENCY} + {@code @Scheduled}</td></tr>
 *   <tr><td>{@code true}（<b>将来</b>）</td><td>XXL-Job 调度中心</td>
 *       <td>本类 {@link #execute()} 上的 {@code @XxlJob("grantConsistency")}</td></tr>
 * </table>
 * <p>{@code XxlJobHandlerRegistryTest#handlerNamesArePinned} 把 handler 名集合钉死，
 * 新增 Job 而不去登记会立刻红 —— <b>那正是提醒</b>。
 *
 * <h2>无会话：逐租户进入，<b>不开跨租户逃生舱</b></h2>
 * <p>契约 §2.8 规则 1/2：上下文<b>必须由数据显式携带</b>，且<b>按租户分片执行</b>。
 * 做法与 {@code AnonymizeArchivedStudentJob} 逐字相同 —— 先取租户清单
 *（{@code sys_tenant} 不带 {@code tenant_id} 列、压根不进插件），
 * 再逐个 {@code runWithTenant} 包住扫描。
 *
 * <p><b>刻意不用 {@code TenantHelper.ignore()}</b>：它是逃生舱，每新增一处都要能说清
 * 「为什么这个查询<b>非跨租户不可</b>」。本任务并不需要跨租户<b>查询</b>——
 * 它只是需要<b>依次进入</b>每个租户，这两件事形似而不同。
 *
 * <h2>两个计数、两个 Gauge，中间不相加</h2>
 * <p>{@code grant_dangling_count}（真悬挂，目标值恒 0，<b>进告警</b>）与
 * {@code grant_cross_scope_count}（跨管辖，节点移动的合法产物，<b>不进告警</b>）
 * 从头到尾是两个变量。合并会让任何一次教师调岗都使指标永久非 0 —— F-20 踩过一次。
 *
 * <h2>只告警不自动处理</h2>
 * <p>PRD FR-7 规则 3/5：巡检<b>不改任何授权行、不动任何学习记录</b>。
 * 处置动作在管理端（一键回收走接口 39、补授上级走接口 38）——
 * 复用既有接口，<b>不为巡检另开一套语义</b>。
 */
@Component
public class GrantConsistencyJob {

    private static final Logger log = LoggerFactory.getLogger(GrantConsistencyJob.class);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GrantHealthService healthService;
    private final GrantTenantMapper tenantMapper;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * 每个租户最近一轮的两个计数 —— Gauge 读它。
     *
     * <p><b>Gauge 必须注册一次、之后只更新值</b>：每轮重新 {@code register} 会在
     * Micrometer 里留下同名同标签的重复计量，取值行为依实现而定 ——
     * 那是一类「指标看着有、值是错的」的静默故障。
     */
    private final ConcurrentHashMap<Long, AtomicInteger> danglingByTenant = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> crossScopeByTenant = new ConcurrentHashMap<>();

    public GrantConsistencyJob(GrantHealthService healthService,
                               GrantTenantMapper tenantMapper,
                               StringRedisTemplate redisTemplate,
                               MeterRegistry meterRegistry) {
        this.healthService = healthService;
        this.tenantMapper = tenantMapper;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    @XxlJob("grantConsistency")
    public void execute() {
        run();
    }

    /**
     * 与 {@link #execute()} 分开，供测试直接调用（不经调度器）。
     *
     * @return 本轮扫过的租户数
     */
    public int run() {
        List<Long> tenantIds = tenantMapper.selectActiveTenantIds();
        for (Long tenantId : tenantIds) {
            try {
                TenantHelper.runWithTenant(tenantId, () -> scanTenant(tenantId));
            } catch (RuntimeException e) {
                // 单租户失败不拖垮整批：下一轮会重扫。但必须有人看见 ——
                // 连续失败意味着那个机构的悬挂授权【从此没人巡检】，而指标会停在上一轮的值
                log.error("授权健康度巡检失败 tenantId={}（下一轮会重扫；"
                        + "连续失败意味着该租户的指标停在上一轮的值）", tenantId, e);
            }
        }
        log.info("授权健康度巡检完成：{} 个租户", tenantIds.size());
        return tenantIds.size();
    }

    private void scanTenant(Long tenantId) {
        GrantHealthService.HealthScan scan = healthService.scan();
        int dangling = scan.dangling().size();
        int crossScope = scan.crossScope().size();

        gaugeOf(danglingByTenant, MetricsRegistry.GRANT_DANGLING_COUNT, tenantId).set(dangling);
        gaugeOf(crossScopeByTenant, MetricsRegistry.GRANT_CROSS_SCOPE_COUNT, tenantId).set(crossScope);

        for (GrantHealthMapper.NodeRowCount row : healthService.rowsPerNode()) {
            meterRegistry.summary(MetricsRegistry.GRANT_ROWS_PER_NODE,
                    MetricsRegistry.TAG_TENANT, String.valueOf(tenantId)).record(row.getRows_());
        }

        redisTemplate.opsForValue().set(RedisKeys.grantHealthLastRun(tenantId),
                LocalDateTime.now().format(TIME_FMT));

        if (dangling > 0) {
            // 真悬挂的目标值【恒为 0】（契约 §7.1）。这条 WARN 与 Gauge 是两条独立的路 ——
            // 指标被关掉时日志还在
            log.warn("租户 {} 发现【真悬挂】授权 {} 条（目标值恒 0，契约 §2.5 规则 6）："
                            + "处置走管理端 A14 页面，一键回收=接口 39、补授上级=接口 38",
                    tenantId, dangling);
        }
        if (crossScope > 0) {
            // 跨管辖【只作待办，不进告警】—— INFO 级
            log.info("租户 {} 有跨管辖授权 {} 条（节点移动的合法产物，只作待办，不计入一致性指标）",
                    tenantId, crossScope);
        }
    }

    /** 注册一次、之后只更新值（见字段注释）。 */
    private AtomicInteger gaugeOf(ConcurrentHashMap<Long, AtomicInteger> holder,
                                  String metric, Long tenantId) {
        return holder.computeIfAbsent(tenantId, id -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder(metric, value, AtomicInteger::get)
                    .tag(MetricsRegistry.TAG_TENANT, String.valueOf(id))
                    .register(meterRegistry);
            return value;
        });
    }
}
