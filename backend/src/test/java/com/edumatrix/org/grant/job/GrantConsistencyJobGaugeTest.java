package com.edumatrix.org.grant.job;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.metrics.MetricsRegistry;
import com.edumatrix.org.grant.mapper.GrantTenantMapper;

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
 * 这几条路径都不进 {@code scanTenant()}（0 租户或取租户就抛），构造器也只做赋值与注册。
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
}
