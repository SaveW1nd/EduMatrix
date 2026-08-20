package com.edumatrix.org.grant.job;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.metrics.MetricsRegistry;
import com.edumatrix.org.grant.mapper.GrantTenantMapper;
import com.edumatrix.org.grant.service.GrantHealthService;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code grant_consistency_last_run_epoch_seconds} —— job 级存活信号（F-101）。
 *
 * <h2>为什么是单元测试而不是 IT</h2>
 * <p>它验的是<b>注册时机与初值</b>，与数据库无关；而且必须在<b>「一次都没跑过」</b>的状态下断言，
 * 那在共享 Spring 上下文的 IT 里做不到 —— 别的用例调过 {@code run()} 之后，
 * 这个 Gauge 就已经有值了，断言会随执行顺序变绿变红。
 * 每条用例自己 {@code new} 一个 {@link SimpleMeterRegistry} 与一个 Job，<b>与顺序无关</b>。
 *
 * <p>{@code healthService} / {@code redisTemplate} 传 {@code null} 是安全的：
 * 那几条路径都不进 {@code scanTenant()}（0 租户或取租户就抛），构造器也只做赋值与注册。
 * <b>唯一真的进得去的那条</b>（{@link #signalMeansRanToCompletionNotRanToStart}）传的是
 * {@link #diesDuringScan()}，在碰到 {@code redisTemplate} 之前就抛了。
 *
 * <h2>三颗钉子，钉的是三件不同的事</h2>
 * <table border="1">
 *   <caption>F-101 定案的三个属性，各有一条用例与一次变异</caption>
 *   <tr><th>属性</th><th>用例</th><th>变异</th></tr>
 *   <tr><td><b>初值</b></td><td>{@link #gaugeExistsAtConstructionWithZero}</td>
 *       <td>M26 改成 {@code System.currentTimeMillis()}</td></tr>
 *   <tr><td><b>单位</b></td><td>{@link #valueIsEpochSecondsNotMillis}</td>
 *       <td>M27 把 set 挪进 {@code scanTenant()}</td></tr>
 *   <tr><td><b>位置</b></td><td>{@link #signalMeansRanToCompletionNotRanToStart}</td>
 *       <td>M28 把 set 挪到循环<b>前</b></td></tr>
 * </table>
 */
class GrantConsistencyJobGaugeTest {

    private static final String METRIC = MetricsRegistry.GRANT_CONSISTENCY_LAST_RUN_EPOCH_SECONDS;

    /** 返回固定租户清单的桩。 */
    private static GrantTenantMapper tenants(List<Long> ids) {
        return () -> ids;
    }

    private static GrantConsistencyJob job(MeterRegistry registry, GrantTenantMapper mapper) {
        return new GrantConsistencyJob(null, mapper, null, registry);
    }

    /**
     * 扫描途中的进程级故障。
     *
     * <p><b>必须抛 {@code Error} 而不是 {@code RuntimeException}</b>：后者会被 {@code run()} 里的
     * {@code catch (RuntimeException)} 兜住、循环照常走完，末尾那句 set 于是照常执行 ——
     * set 摆在循环前还是循环后<b>结论恒同</b>，拿它当探针这条用例一个分叉都区分不了。
     */
    private static GrantHealthService diesDuringScan() {
        return new GrantHealthService(null, null) {
            @Override
            public HealthScan scan() {
                throw new Error("探针：扫描途中的进程级故障（等价于进程被杀）");
            }
        };
    }

    @Test
    @DisplayName("⚠ 构造即注册，且初值为 0 ——「从未跑过」必须能被告警看见")
    void gaugeExistsAtConstructionWithZero() {
        MeterRegistry registry = new SimpleMeterRegistry();
        job(registry, tenants(List.of()));

        Gauge gauge = registry.find(METRIC).gauge();
        assertThat(gauge)
                .as("它必须【永远存在】：per-tenant 那两个 Gauge 在「0 租户」与「调度器没触发」"
                        + "两种情况下一条序列都没有，而那与「一切健康」在告警上长得一模一样")
                .isNotNull();
        assertThat(gauge.value())
                .as("初值必须是 0 =「从未跑过」，于是 time() - 0 巨大、告警立刻触发。\\n"
                        + "【绝不能照抄模块 09 的 System.currentTimeMillis()】——"
                        + "那对 10 秒一轮的消费者是对的（重启后几秒就有真实值），"
                        + "日任务照抄会让「任务从不触发」在每次重启后被掩盖【一天】；"
                        + "而部署比一天更频繁时【被永久掩盖】：每次发版都重置计时器，"
                        + "告警永远差最后一步，而看起来一切正常")
                .isZero();
    }

    @Test
    @DisplayName("⚠ 0 个租户时 run() 照样更新 job 级信号 ——「跑完了没租户」≠「压根没跑」")
    void zeroTenantsStillUpdatesJobLevelGauge() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GrantConsistencyJob job = job(registry, tenants(List.of()));

        assertThat(job.run()).isZero();

        assertThat(registry.find(METRIC).gauge().value())
                .as("生产上线初期就是 0 租户。若这个 set 被挪进 scanTenant()，"
                        + "0 租户下它永远是 0 —— 与「巡检从来没跑过」逐字节相同")
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("取租户清单就抛 → 信号【保持陈旧】，26h 后告警触发（这是期望行为）")
    void tenantListFailureLeavesSignalStale() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GrantConsistencyJob job = job(registry, () -> {
            throw new IllegalStateException("探针：取租户清单失败");
        });

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);

        assertThat(registry.find(METRIC).gauge().value())
                .as("run() 中断则末尾那句不执行 —— 信号变陈旧正是我们要的："
                        + "「巡检启动了但没跑完」和「没启动」对运维是同一件事，都要有人去看")
                .isZero();
    }

    @Test
    @DisplayName("值是 epoch【秒】不是毫秒 —— 告警写的是 time() - x，单位错了阈值就没意义")
    void valueIsEpochSecondsNotMillis() {
        MeterRegistry registry = new SimpleMeterRegistry();
        long before = java.time.Instant.now().getEpochSecond();
        job(registry, tenants(List.of())).run();
        long after = java.time.Instant.now().getEpochSecond();

        double value = registry.find(METRIC).gauge().value();
        assertThat(value)
                .as("毫秒值会让 time() - x 变成一个巨大的负数，告警【永远不触发】——"
                        + "而那与「一切正常」在监控上同样长得一模一样")
                .isBetween((double) before, (double) after);
    }

    @Test
    @DisplayName("⚠ 钉【位置】：扫描途中中断 → 信号仍为 0 ——「跑完了」不是「开始跑了」")
    void signalMeansRanToCompletionNotRanToStart() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GrantConsistencyJob job = new GrantConsistencyJob(
                diesDuringScan(), tenants(List.of(1971L)), null, registry);

        assertThatThrownBy(job::run)
                .as("探针必须是 Error —— 理由见 diesDuringScan() 的注释")
                .isInstanceOf(Error.class);

        assertThat(registry.find(METRIC).gauge().value())
                .as("【这条钉的是 set 的位置，不是异常处理】—— 与 gaugeExistsAtConstructionWithZero "
                        + "钉初值（M26）、valueIsEpochSecondsNotMillis 钉单位（M27）是同一组三件事：\n"
                        + "位置决定这个信号【叫什么名字】：写在循环【后】它是「上次跑完」，"
                        + "挪到循环【前】就变成「上次开始跑」，而告警文案与阈值都是按前者写的。\n"
                        + "分叉场景是【进程在扫描途中被杀】：多租户时一轮可能跑几分钟，发版重启正好撞上 —— "
                        + "循环前的版本已经记下「跑过了」、告警安静 26 小时，而大部分租户根本没扫到。\n"
                        + "没有这条，将来有人做个很合理的重构（「记一个开始时刻和一个结束时刻吧」）"
                        + "把它上提，全绿通过。")
                .isZero();
    }
}
