package com.edumatrix.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import com.edumatrix.common.config.SchedulerConfig;
import com.edumatrix.common.config.XxlJobConfig;
import com.edumatrix.org.grant.job.GrantConsistencyJob;
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
    private final ApplicationContextRunner runner = runnerWith(mock(VodEventConsumeJob.class));

    /**
     * 同一套装配，只换消费 Job 的 mock。
     *
     * <p><b>不要在共享的 {@code runner} 上再 {@code withBean} 一个同类型的 Bean</b> ——
     * 那会注册成两个候选，上下文直接起不来，而表现是「任务没跑」这种看着像被测行为的假象
     * （本条实测踩过：第一版这么写，失败信息是「消费任务 5 秒内一次都没跑」，
     * 而真正的原因是上下文压根没起来）。
     */
    private static ApplicationContextRunner runnerWith(VodEventConsumeJob consumeJob) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("xxl.job.executor.appname=edumatrix-test",
                        "xxl.job.executor.port=0")
                .withBean(AnonymizeArchivedStudentJob.class, () -> mock(AnonymizeArchivedStudentJob.class))
                .withBean(TempFileCleanupJob.class, () -> mock(TempFileCleanupJob.class))
                .withBean(VodEventConsumeJob.class, () -> consumeJob)
                .withBean(GrantConsistencyJob.class, () -> mock(GrantConsistencyJob.class))
                .withUserConfiguration(SchedulerConfig.class, ScheduledJobTrigger.class, XxlJobConfig.class);
    }

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
                        .as("两条触发路径的配置类【一个都没装配】：props=%s。"
                                + "本条只承诺 Bean 层面 —— 「任务是否真的被注册成 CronTask」"
                                + "由 cronTasksAreActuallyRegistered 验，别把两件事混成一句话",
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
    // Bean 在 ≠ 任务被注册：@Scheduled 真的生效了吗
    // =====================================================================

    /**
     * <b>Bean 存在只说明配置类被装配了，不说明 {@code @Scheduled} 被注册成了任务。</b>
     *
     * <p>上面那几条数的是 Bean。而「{@code @Scheduled} 到底有没有生效」是另一件事，
     * 它有<b>好几种静默失效的方式</b>：
     * <ul>
     *   <li>{@code @EnableScheduling} 被挪走或删掉 —— 配置类照样装配，注解<b>一条都不注册</b>；</li>
     *   <li>方法签名不合法（非 void、带参）—— Spring 在注册时抛错或跳过；</li>
     *   <li>cron 常量被改错 —— 任务注册了，但在错误的时刻跑。</li>
     * </ul>
     * <p>这三种的共同表现都是<b>应用启动正常、日志里那行「定时触发 = Spring 调度」照样打出来</b>，
     * 而任务不跑或在错误时刻跑。所以这里直接问 Spring 自己：
     * {@link ScheduledTaskHolder#getScheduledTasks()} 里有哪些 {@link CronTask}、表达式是什么。
     *
     * <p><b>顺带钉住了 cron 与常量的一致性</b>：断言比的是常量本身，
     * 所以「常量改了而调度中心的登记值没跟着改」这件事本测试拦不住（那只能靠人），
     * 但「注解上写的和常量不是一个值」会红。
     */
    @Test
    @DisplayName("@Scheduled 真的被注册成三条 CronTask，且表达式 == 三个常量（删 @EnableScheduling 会红）")
    void cronTasksAreActuallyRegistered() {
        runner.run(context -> {
            List<String> expressions = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream())
                    .map(ScheduledTask::getTask)
                    .filter(CronTask.class::isInstance)
                    .map(CronTask.class::cast)
                    .map(CronTask::getExpression)
                    .sorted()
                    .toList();

            assertThat(expressions)
                    .as("Spring 实际注册的 CronTask 与预期不符 —— "
                            + "配置类装配了但 @Scheduled 没生效（@EnableScheduling 被删？"
                            + "方法签名不合法？），或 cron 与常量不是一个值。"
                            + "这几种的共同表现都是「启动正常、日志照打、任务不跑」")
                    .containsExactly(
                            ScheduledJobTrigger.CRON_ANONYMIZE_ARCHIVED_STUDENT,   // 0 30 2 * * *
                            ScheduledJobTrigger.CRON_TEMP_FILE_CLEANUP,            // 0 30 3 * * *
                            ScheduledJobTrigger.CRON_GRANT_CONSISTENCY);           // 0 30 4 * * *
        });
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
        VodEventConsumeJob consume = mock(VodEventConsumeJob.class);
        GrantConsistencyJob grantConsistency = mock(GrantConsistencyJob.class);
        when(cleanup.run()).thenReturn(new TempFileCleanupJob.CleanupSummary(0, 0));
        when(consume.run()).thenReturn(0);
        when(grantConsistency.run()).thenReturn(0);

        ScheduledJobTrigger trigger =
                new ScheduledJobTrigger(anonymize, cleanup, consume, grantConsistency);
        trigger.triggerAnonymizeArchivedStudent();
        trigger.triggerTempFileCleanup();
        trigger.triggerVodEventConsume();
        trigger.triggerGrantConsistency();

        verify(anonymize).run();
        verify(anonymize, never()).execute();
        verify(grantConsistency).run();
        verify(grantConsistency, never()).execute();
        verify(cleanup).run();
        verify(cleanup, never()).execute();
        verify(consume).run();
        verify(consume, never()).execute();
    }

    @Test
    @DisplayName("④' 任务失败不会让调度停摆，异常被吞成 ERROR 且不外抛")
    void oneFailureDoesNotBreakTheScheduler() {
        AnonymizeArchivedStudentJob anonymize = mock(AnonymizeArchivedStudentJob.class);
        TempFileCleanupJob cleanup = mock(TempFileCleanupJob.class);
        VodEventConsumeJob consume = mock(VodEventConsumeJob.class);
        when(anonymize.run()).thenThrow(new IllegalStateException("探针：本次执行失败"));
        when(cleanup.run()).thenReturn(new TempFileCleanupJob.CleanupSummary(0, 0));
        when(consume.run()).thenReturn(0);

        ScheduledJobTrigger trigger = new ScheduledJobTrigger(anonymize, cleanup, consume,
                mock(GrantConsistencyJob.class));

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
    // 模块 09：第三条任务，以及它跑在【哪个】线程池上
    // =====================================================================

    /**
     * <b>补上 {@link #cronTasksAreActuallyRegistered} 的一个洞</b>：那一条
     * {@code .filter(CronTask.class::isInstance)}，只看得见 cron 触发器。
     * 模块 09 的消费任务用的是 {@code fixedDelay}，注册成 {@code FixedDelayTask} ——
     * <b>它加进来，那条断言不会红</b>。而「加了一个 Job 必须有人动手改测试」正是
     * {@code XxlJobHandlerRegistryTest} 与那条断言共同守的东西。
     *
     * <p>所以这里数<b>全部</b> {@link ScheduledTask}，不按类型过滤，并逐条钉住触发器的类型与值。
     * <b>原来那条一字未改</b>——放松它等于把守卫拆了。
     *
     * <p>变异（需方点名的杀死条件）：把 {@code triggerVodEventConsume} 上的
     * {@code @Scheduled} 删掉 → 任务数变 2 → 本条红。
     */
    @Test
    @DisplayName("全部 ScheduledTask 恰好四条：三条 cron + 一条 fixedDelay 10s（摘掉任一个都会红）")
    void allScheduledTasksArePinned() {
        runner.run(context -> {
            List<Task> tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream())
                    .map(ScheduledTask::getTask)
                    .toList();

            assertThat(tasks)
                    .as("注册的调度任务数与预期不符。加/删 Job 时：① 去调度中心登记 handler 名与 cron；"
                            + "② 更新 XxlJobHandlerRegistryTest 的清单；③ 更新本条与 05-工程结构.md §H")
                    .hasSize(4);

            List<String> crons = tasks.stream()
                    .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                    .map(CronTask::getExpression).sorted().toList();
            assertThat(crons).containsExactly(
                    ScheduledJobTrigger.CRON_ANONYMIZE_ARCHIVED_STUDENT,
                    ScheduledJobTrigger.CRON_TEMP_FILE_CLEANUP,
                    ScheduledJobTrigger.CRON_GRANT_CONSISTENCY);

            List<Duration> delays = tasks.stream()
                    .filter(FixedDelayTask.class::isInstance).map(FixedDelayTask.class::cast)
                    .map(FixedDelayTask::getIntervalDuration).toList();
            assertThat(delays)
                    .as("转码事件消费必须是 fixedDelay（完成后再等 10s，天然背压、永不重叠），"
                            + "且间隔 == ScheduledJobTrigger.FIXED_DELAY_VOD_EVENT_CONSUME")
                    .containsExactly(Duration.ofMillis(
                            Long.parseLong(ScheduledJobTrigger.FIXED_DELAY_VOD_EVENT_CONSUME)));
        });
    }

    /**
     * <b>消费任务必须真的跑在 {@code vodEventTaskScheduler} 上。</b>
     *
     * <p>不去反射「注册时绑了哪个 Bean」，而是<b>看它实际跑在哪个线程上</b> ——
     * 线程名前缀是两个池唯一的、运行期可观测的区别，也是生产上排查卡死时的诊断入口。
     * {@code fixedDelay} 任务的 {@code initialDelay} 为 0，上下文一起来它就跑第一轮，
     * 所以这里只需等它跑到。
     *
     * <p><b>两个变异各自都能杀死本条</b>：
     * <ol>
     *   <li>去掉 {@code @Scheduled} 上的 {@code scheduler = ...} → 消费任务落到默认池，
     *       线程名变成 {@code edumatrix-sched-*} → 红；</li>
     *   <li>删掉 {@code SchedulerConfig} 里显式的 {@code @Bean("taskScheduler")} →
     *       Boot 的 {@code TaskSchedulerConfiguration} 因
     *       {@code @ConditionalOnMissingBean({TaskScheduler, ScheduledExecutorService})} 退避，
     *       容器里只剩一个调度器，三条任务重新挤回同一个池 → 下面那条 bean 断言红。
     *       <b>这一条拦的正是「以为隔离了、实际没隔离」</b>，而它不会报任何错。</li>
     * </ol>
     *
     * <h2>⚠ 两条断言缺一不可 —— 别把 {@code hasBean} 那条当成冗余删掉</h2>
     * <p><b>这是实测出来的，不是设计出来的</b>：做变异 ② 时，
     * 上面那条<b>线程名断言仍然是绿的</b> —— 默认调度器退避消失后，三条任务全落到
     * 剩下的那个池上，而那个池的前缀恰好就是 {@code vod-event-}，
     * 于是「消费任务跑在 vod-event-* 上」照样成立。红的只有 {@code hasBean} 那一条。
     *
     * <p>也就是说：只写线程名断言的话，<b>这个陷阱会安静地通过</b> ——
     * 隔离失效、日志照打、三个任务重新互相阻塞，而测试全绿。
     * 那会是本项目「以为存在、实际从未生效的保障」的<b>未遂第七例</b>，
     * 而拦住它的不是设计，是变异测试。
     *
     * <p><b>线程名那条证明「绑对了」，{@code hasBean} 那条证明「有两个池可绑」</b>。
     * 前者答「消费任务在哪」，后者答「另外两个任务还有没有自己的地方」。删掉任何一条，
     * 另一条都答不出被删掉的那半个问题。
     */
    @Test
    @DisplayName("消费任务实际跑在 vod-event-* 线程上，且两个调度器 Bean 都在（少一个就是没隔离）")
    void vodConsumeRunsOnItsOwnScheduler() {
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        VodEventConsumeJob consume = mock(VodEventConsumeJob.class);
        when(consume.run()).thenAnswer(invocation -> {
            threadName.compareAndSet(null, Thread.currentThread().getName());
            ran.countDown();
            return 0;
        });

        runnerWith(consume).run(context -> {
            assertThat(ran.await(5, TimeUnit.SECONDS))
                    .as("消费任务在 5 秒内一次都没跑 —— fixedDelay 的 initialDelay 是 0，"
                            + "起来就该跑第一轮")
                    .isTrue();

            assertThat(threadName.get())
                    .as("消费任务没有跑在自己的调度器上。它会调云端 API，卡住是常态；"
                            + "与两个合规日任务共用一个单线程池的话，它一卡，"
                            + "30 日不可逆脱敏与 7 天清理就永远不触发，且没有任何东西会报告")
                    .startsWith("vod-event-");

            // 默认池必须【也】显式存在，且名字就是 taskScheduler ——
            // 少了它，自动配置退避，隔离静默失效
            assertThat(context).hasBean(SchedulerConfig.DEFAULT_SCHEDULER);
            assertThat(context).hasBean(SchedulerConfig.VOD_EVENT_SCHEDULER);
            assertThat(context.getBean(SchedulerConfig.DEFAULT_SCHEDULER))
                    .as("两个调度器必须是不同实例，否则隔离只是名字上的")
                    .isNotSameAs(context.getBean(SchedulerConfig.VOD_EVENT_SCHEDULER));
        });
    }

    /**
     * <b>行为层的证明：一个任务卡死，另外两个还跑不跑。</b>
     *
     * <p>上面两条数的是装配结果。本条不起 Spring 上下文，直接拿两个<b>真的</b>单线程
     * {@code ThreadPoolTaskScheduler}，把消费任务换成一个<b>永不返回</b>的 mock，
     * 再看日任务还走不走得动。这才是那句承诺（「消费任务卡住，两个合规任务照常」）
     * 的直接证据 —— 装配对了但线程池实现有别的坑时，只有这一条会红。
     *
     * <p>变异：把两条注册到<b>同一个</b>调度器 → 日任务一次都跑不到 → 红。
     */
    @Test
    @DisplayName("消费任务卡死时，日任务照常触发（两条注册到同一个池则一次都跑不到）")
    void hangingTaskDoesNotStarveTheOthers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger cleanupRuns = new AtomicInteger();

        AnonymizeArchivedStudentJob anonymize = mock(AnonymizeArchivedStudentJob.class);
        TempFileCleanupJob cleanup = mock(TempFileCleanupJob.class);
        VodEventConsumeJob consume = mock(VodEventConsumeJob.class);
        when(cleanup.run()).thenAnswer(invocation -> {
            cleanupRuns.incrementAndGet();
            return new TempFileCleanupJob.CleanupSummary(0, 0);
        });
        when(consume.run()).thenAnswer(invocation -> {
            release.await();          // 卡死：模拟云端 API 挂住，永不返回
            return 0;
        });

        ScheduledJobTrigger trigger = new ScheduledJobTrigger(anonymize, cleanup, consume,
                mock(GrantConsistencyJob.class));
        ThreadPoolTaskScheduler vodPool = pool("probe-vod-");
        ThreadPoolTaskScheduler dailyPool = pool("probe-daily-");
        try {
            vodPool.scheduleWithFixedDelay(trigger::triggerVodEventConsume, Duration.ofMillis(20));
            dailyPool.scheduleWithFixedDelay(trigger::triggerTempFileCleanup, Duration.ofMillis(50));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cleanupRuns.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(cleanupRuns.get())
                    .as("消费任务卡死期间日任务一次都没跑到 —— 两者共用了同一个线程池。"
                            + "后果：30 日不可逆脱敏（《个保法》第 31 条 / 契约 §7.2 第 3 条）与"
                            + "敏感文件 7 天清理永远不触发，而应用一切正常")
                    .isGreaterThanOrEqualTo(3);
        } finally {
            release.countDown();
            vodPool.shutdown();
            dailyPool.shutdown();
        }
    }

    private static ThreadPoolTaskScheduler pool(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.initialize();
        return scheduler;
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
