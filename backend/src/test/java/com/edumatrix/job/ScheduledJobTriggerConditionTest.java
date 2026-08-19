package com.edumatrix.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.ActiveProfiles;

import com.edumatrix.common.config.XxlJobConfig;
import com.edumatrix.org.member.job.AnonymizeArchivedStudentJob;
import com.edumatrix.support.IntegrationTest;

/**
 * 两条触发路径<b>互斥</b>，且过渡触发器<b>不在测试环境激活</b>。
 *
 * <h2>为什么这组测试是必需的，而不是"读代码就知道互斥"</h2>
 * <p>两条路径同时生效<b>不会报错</b> —— 任务只是每天跑两遍。而这两个 Job 恰好都幂等
 * （脱敏靠 {@code anonymized_at IS NULL} 收口、清理靠 {@code deleted_at} 收口），
 * 所以<b>真跑两遍也看不出来</b>：没有异常、没有重复数据、没有任何指标异动。
 * 这正是本项目定义的头号故障形态，只能靠<b>实测装配结果</b>钉住。
 *
 * <p>所以本类用 {@link ApplicationContextRunner} 真的把上下文起来，
 * 按不同配置<b>数 Bean</b>，而不是去读 {@code @ConditionalOnProperty} 的注解值
 * （那只能证明"注解写对了"，证明不了"Spring 按它装配"）。
 */
class ScheduledJobTriggerConditionTest {

    /**
     * 只装两个候选配置 + 两个 mock 出来的 Job。
     *
     * <p>不引入完整应用上下文：那会连 MySQL 与 Redis，而这里要验的只是
     * 「按 {@code xxl.job.enabled} 与 profile，哪个 Bean 被装配」。
     * {@code TaskSchedulingAutoConfiguration} 要带上 —— {@code @EnableScheduling}
     * 需要它提供 {@code TaskScheduler}，不带的话上下文会以一个与被测无关的原因失败。
     *
     * <p><b>{@code executor.port=0}</b>：{@code enabled=true} 的那几条会真的把执行器
     * {@code start()} 起来并 bind 端口。写死 9999 的话，端口被占（本机跑着真执行器、
     * 或两个构建并行）就会让这组用例<b>偶发失败，而失败原因与被测的互斥性毫无关系</b>。
     * 传 0 时 XXL-Job <b>自己挑一个可用端口</b>（实测日志：两个上下文分别拿到 9999 与 10000）——
     * 不是 OS 分配，是它内部的可用端口探测，效果相同且不会撞。
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withPropertyValues("xxl.job.executor.appname=edumatrix-test",
                    "xxl.job.executor.port=0")
            .withBean(AnonymizeArchivedStudentJob.class, () -> mock(AnonymizeArchivedStudentJob.class))
            .withBean(TempFileCleanupJob.class, () -> mock(TempFileCleanupJob.class))
            .withUserConfiguration(ScheduledJobTrigger.class, XxlJobConfig.class);

    // =====================================================================
    // ① / ② 互斥
    // =====================================================================

    @Test
    @DisplayName("① enabled 未配 → 过渡触发器在、XXL-Job 执行器不在")
    void springTriggerIsActiveWhenFlagIsAbsent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ScheduledJobTrigger.class);
            assertThat(context)
                    .as("未配 xxl.job.enabled 时不该自作主张启用一个会向外连的执行器")
                    .doesNotHaveBean(XxlJobConfig.class);
        });
    }

    @Test
    @DisplayName("① enabled=false（现状，含 application-test.yml 的取值）→ 过渡触发器在、执行器不在")
    void springTriggerIsActiveWhenFlagIsFalse() {
        runner.withPropertyValues("xxl.job.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(ScheduledJobTrigger.class);
            assertThat(context).doesNotHaveBean(XxlJobConfig.class);
        });
    }

    /**
     * <b>互斥性的正面证明</b>：翻到 {@code true} 之后，Spring 那条<b>必须消失</b>。
     *
     * <p>没有这一条，把 {@code havingValue} 从 {@code "false"} 改成 {@code "true"}
     * （或把 {@code matchIfMissing} 改成让两者都成立）之后，
     * 两条路径会同时装配 —— 而那不会报错，任务只是跑两遍。
     */
    @Test
    @DisplayName("② enabled=true → XXL-Job 执行器在、过渡触发器【消失】（双触发不会报错，只能靠这条钉住）")
    void springTriggerDisappearsWhenXxlJobTakesOver() {
        runner.withPropertyValues("xxl.job.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(XxlJobConfig.class);
            assertThat(context)
                    .as("两条触发路径同时生效不会报错，任务只是每天跑两遍；"
                            + "而这两个 Job 都幂等，所以真跑两遍也看不出来")
                    .doesNotHaveBean(ScheduledJobTrigger.class);
        });
    }

    @Test
    @DisplayName("②' 任何配置下两个 Bean 都不会同时存在（把三种取值一起过一遍）")
    void neverBothAtOnce() {
        for (String[] props : new String[][]{{}, {"xxl.job.enabled=false"}, {"xxl.job.enabled=true"}}) {
            runner.withPropertyValues(props).run(context -> {
                boolean spring = context.getBeanNamesForType(ScheduledJobTrigger.class).length > 0;
                boolean xxl = context.getBeanNamesForType(XxlJobConfig.class).length > 0;
                assertThat(spring && xxl)
                        .as("两条触发路径同时装配：props=%s", java.util.Arrays.toString(props))
                        .isFalse();
                assertThat(spring || xxl)
                        .as("两条触发路径都没装配 —— 那两个合规任务谁都不会触发：props=%s",
                                java.util.Arrays.toString(props))
                        .isTrue();
            });
        }
    }

    // =====================================================================
    // ③ 测试环境不被误触发
    // =====================================================================

    /**
     * <b>那个坑</b>：{@code application-test.yml} 逐字写着 {@code xxl.job.enabled: false}，
     * 所以反向门控在测试期间是<b>满足</b>的。没有 {@code @Profile("!test")} 的话，
     * 一旦某条 cron 在测试运行时刚好命中，清理任务会删测试数据、脱敏任务会改测试数据 ——
     * 而表现是<b>随机的偶发失败、过后极难复现</b>。
     */
    @Test
    @DisplayName("③ test profile 下过渡触发器【不装配】（去掉 @Profile(\"!test\") 会红）")
    void springTriggerIsNotActiveUnderTestProfile() {
        runner.withPropertyValues("spring.profiles.active=test", "xxl.job.enabled=false")
                .run(context -> {
                    assertThat(context)
                            .as("测试期间被触发的后果是随机偶发失败，过后极难复现")
                            .doesNotHaveBean(ScheduledJobTrigger.class);
                    assertThat(context).doesNotHaveBean(XxlJobConfig.class);
                });
    }

    /**
     * ③ 的前提：{@code @Profile("!test")} 只在「每个 IT 都激活了 {@code test} profile」时有效。
     *
     * <p>全库的 IT 都走 {@code support/IntegrationTest}，而它<b>硬编码</b>了
     * {@code @ActiveProfiles("test")}。把那个注解删掉，上面那条门控对全部 IT 失效 ——
     * 所以这里把它一起钉住。
     */
    @Test
    @DisplayName("③' @IntegrationTest 仍然硬编码 @ActiveProfiles(\"test\")（删掉它，③ 的前提就没了）")
    void integrationTestAnnotationStillPinsTheTestProfile() {
        ActiveProfiles profiles = IntegrationTest.class.getAnnotation(ActiveProfiles.class);

        assertThat(profiles)
                .as("@IntegrationTest 不再激活 test profile —— ScheduledJobTrigger 的 "
                        + "@Profile(\"!test\") 门控对全部集成测试同时失效")
                .isNotNull();
        assertThat(profiles.value()).containsExactly("test");
    }

    // =====================================================================
    // ④ 委派 run() 而不是 execute()
    // =====================================================================

    /**
     * 走 {@code execute()} 会变成「Spring 调度触发 XXL-Job 薄壳」这种将来一定有人看不懂的路径，
     * 而它<b>照样能跑通</b> —— 所以只能靠 mock 验证调的到底是哪个方法。
     */
    @Test
    @DisplayName("④ 委派方法调 run() 而不是 execute()")
    void delegatesToRunNotExecute() {
        AnonymizeArchivedStudentJob anonymize = mock(AnonymizeArchivedStudentJob.class);
        TempFileCleanupJob cleanup = mock(TempFileCleanupJob.class);
        when(cleanup.run()).thenReturn(new TempFileCleanupJob.CleanupSummary(0, 0));

        ScheduledJobTrigger trigger = new ScheduledJobTrigger(anonymize, cleanup);
        trigger.triggerAnonymizeArchivedStudent();
        trigger.triggerTempFileCleanup();

        verify(anonymize).run();
        verify(anonymize, never()).execute();
        verify(cleanup).run();
        verify(cleanup, never()).execute();
    }

    @Test
    @DisplayName("④' 任务失败不会让调度停摆，异常被吞成 ERROR 且不外抛")
    void oneFailureDoesNotBreakTheScheduler() {
        AnonymizeArchivedStudentJob anonymize = mock(AnonymizeArchivedStudentJob.class);
        TempFileCleanupJob cleanup = mock(TempFileCleanupJob.class);
        when(anonymize.run()).thenThrow(new IllegalStateException("探针：本次执行失败"));
        when(cleanup.run()).thenReturn(new TempFileCleanupJob.CleanupSummary(0, 0));

        ScheduledJobTrigger trigger = new ScheduledJobTrigger(anonymize, cleanup);

        org.assertj.core.api.Assertions
                .assertThatCode(trigger::triggerAnonymizeArchivedStudent)
                .as("外抛的话日志里出现的是框架栈，排查的人第一反应会是「调度坏了」"
                        + "而不是「这个任务本次失败了」")
                .doesNotThrowAnyException();
        // 另一个任务不受影响
        org.assertj.core.api.Assertions
                .assertThatCode(trigger::triggerTempFileCleanup)
                .doesNotThrowAnyException();
    }

    // =====================================================================
    // cron 常量
    // =====================================================================

    @Test
    @DisplayName("cron 只有一处定义，且与 Job 的 Javadoc / §H 的时刻一致（切换时照抄这两个值）")
    void cronExpressionsMatchTheDocumentedSchedule() {
        // TempFileCleanupJob 的 Javadoc（Q-2 定案）：每日 03:30
        assertThat(ScheduledJobTrigger.CRON_TEMP_FILE_CLEANUP).isEqualTo("0 30 3 * * *");
        // 脱敏任务只写「每日一次」，本值由模块 05 定，与 DailySettleJob 00:30 / 清理 03:30 错开
        assertThat(ScheduledJobTrigger.CRON_ANONYMIZE_ARCHIVED_STUDENT).isEqualTo("0 30 2 * * *");
        // 两个任务不同时刻 —— Spring 默认调度线程池只有 1 个线程，同刻会串行排队
        assertThat(ScheduledJobTrigger.CRON_ANONYMIZE_ARCHIVED_STUDENT)
                .isNotEqualTo(ScheduledJobTrigger.CRON_TEMP_FILE_CLEANUP);
    }
}
