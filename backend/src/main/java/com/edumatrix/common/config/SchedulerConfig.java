package com.edumatrix.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 调度线程池 —— <b>两个，互相隔离</b>。
 *
 * <table border="1">
 *   <caption>谁在哪个池上</caption>
 *   <tr><th>Bean</th><th>线程</th><th>线程名前缀</th><th>跑什么</th></tr>
 *   <tr><td>{@code taskScheduler}</td><td>1</td><td>{@code edumatrix-sched-}</td>
 *       <td>{@code anonymizeArchivedStudent} 02:30、{@code tempFileCleanup} 03:30</td></tr>
 *   <tr><td>{@code vodEventTaskScheduler}</td><td>1</td><td>{@code vod-event-}</td>
 *       <td>{@code vodEventConsume}，10s 一轮</td></tr>
 * </table>
 *
 * <h2>为什么必须隔离</h2>
 * <p>{@code ScheduledJobTrigger} 的 Javadoc 逐字写着「Spring 默认调度线程池只有 1 个线程，
 * 串行执行 —— 这在两个日任务的量级上是合适的，但<b>别再往里塞第三个高频任务</b>」。
 * 模块 09 的消费任务正是那个高频任务，且它<b>要调云端 API</b>，卡住是常态而非异常。
 * 混在同一个池里，后果是双向的：
 * <ul>
 *   <li>消费任务卡住 → 两个<b>合规</b>任务永远不触发（30 日不可逆脱敏是《个保法》第 31 条
 *       与契约 §7.2 第 3 条的承诺，7 天清理是 00-通用约定 §7.4 第 6 条），
 *       <b>而没有任何东西会报告它</b>；</li>
 *   <li>某个日任务跑久了（清理要遍历全部租户）→ 消费停摆，队列积压。</li>
 * </ul>
 * <p>隔离之后：<b>任一任务卡死，另外两个照常</b>。两个日任务之间<b>仍然串行</b>
 * （现状不变，02:30 / 03:30 各错开一小时）。
 * 由 {@code ScheduledJobTriggerConditionTest} 的装配层与行为层各一条实测钉住。
 *
 * <h2>⚠ 为什么必须显式定义<b>两个</b>，而不是只加一个新的</h2>
 * <p>Spring Boot 的 {@code TaskSchedulingConfigurations$TaskSchedulerConfiguration} 上挂的是
 * <pre>@ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})</pre>
 * （反编译 {@code spring-boot-autoconfigure} 的字节码逐字确认）。也就是说
 * <b>只要容器里出现任意一个 {@code TaskScheduler} Bean，默认的 {@code taskScheduler} 就整个退避消失</b>。
 * 那时三条 {@code @Scheduled} 会被 {@code ScheduledAnnotationBeanPostProcessor}
 * 落回<b>同一个</b>调度器 —— <b>不报错、日志照打、隔离静默失效</b>，正是本项目的头号故障形态。
 * 所以这里把默认的那个也显式建出来，且名字<b>必须</b>是
 * {@code ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME}
 * （即 {@code "taskScheduler"}）—— 有多个候选时它按这个名字挑。
 *
 * <h2>⚠ 连带：{@code spring.task.scheduling.pool.size} 从此不再生效</h2>
 * <p>自动配置退避了，<b>没有任何东西在读那个属性</b>。故：
 * <ol>
 *   <li>{@code application.yml} 里<b>不要</b>出现这个键；</li>
 *   <li>线程数与理由写在本类（就是这段）；</li>
 *   <li>{@code 05-工程结构.md} §F4 已登记「本键在本项目无效」（F-67）。</li>
 * </ol>
 * <p>不做这三步，将来有人配 {@code pool.size=4} 会「配了没生效」——
 * 那正是本项目反复点名的「以为存在、实际从未生效」。
 *
 * <h2>线程名前缀是诊断入口</h2>
 * <p>卡死时看线程名即可分辨：{@code vod-event-1} 停在云 SDK 的 socket read 上，
 * 与 {@code edumatrix-sched-1} 停在一条全租户扫描上，处置完全不同。
 */
@Configuration(proxyBeanMethods = false)
public class SchedulerConfig {

    /** 与 {@code ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME} 一致。 */
    public static final String DEFAULT_SCHEDULER = "taskScheduler";

    /** 转码事件消费专用调度器的 Bean 名。{@code @Scheduled(scheduler = ...)} 按它绑定。 */
    public static final String VOD_EVENT_SCHEDULER = "vodEventTaskScheduler";

    /**
     * 两个<b>日</b>任务的调度器。1 个线程 —— 它们各自每天跑一次、各错开一小时，
     * 串行是合适的（F-41 已论证）。<b>本 Bean 的存在本身也是必需的</b>，见类注释。
     */
    @Bean(DEFAULT_SCHEDULER)
    public ThreadPoolTaskScheduler taskScheduler() {
        return build(1, "edumatrix-sched-");
    }

    /**
     * 转码事件消费专用。1 个线程 —— 队列消费<b>不要并发</b>：
     * 同一媒资的两条事件被两个线程同时处理，会把「先失败后成功」的顺序打乱。
     * 单线程 + 单次拉取上限 16 条已足够（03-03 §7.2）。
     */
    @Bean(VOD_EVENT_SCHEDULER)
    public ThreadPoolTaskScheduler vodEventTaskScheduler() {
        return build(1, "vod-event-");
    }

    /**
     * 关闭时<b>等在途任务跑完</b>（上限 20s）。
     *
     * <p>消费任务在「落库成功、还没删消息」这个窗口里被强杀，那条消息会重投一次 ——
     * 有 CAS 幂等兜着不会写错数据，但白跑一次 {@code GetPlayInfo}。
     * 两个日任务被强杀则更差：脱敏跑到一半停下，收口标记没写，下次重扫（可接受但白跑）。
     */
    private static ThreadPoolTaskScheduler build(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        // 关闭中被拒的任务静默丢弃即可：调度任务下一轮还会来，抛异常只会在关机日志里制造噪声
        scheduler.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        return scheduler;
    }
}
