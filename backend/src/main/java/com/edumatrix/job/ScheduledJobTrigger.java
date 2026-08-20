package com.edumatrix.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.edumatrix.common.config.SchedulerConfig;
import com.edumatrix.org.grant.job.GrantConsistencyJob;
import com.edumatrix.org.member.job.AnonymizeArchivedStudentJob;

/**
 * <b>过渡期</b>定时触发器：在调度中心（{@code xxl-job-admin}）落地之前，用 Spring 自带调度
 * 把<b>四个</b> Job 触发起来（三个日任务 + 一个高频任务）。
 * 需方定案（F-41）：<b>暂时不部署调度中心</b>。
 *
 * <h2>两条触发路径<b>互斥</b>，靠 {@code xxl.job.enabled} 一个开关切换</h2>
 * <table border="1">
 *   <caption>任何时刻只有一条存在</caption>
 *   <tr><th>{@code xxl.job.enabled}</th><th>生效的路径</th><th>装配的 Bean</th></tr>
 *   <tr><td>{@code false} 或未配（<b>现状</b>）</td><td>Spring 调度</td>
 *       <td>本类；{@code XxlJobConfig} <b>不装配</b></td></tr>
 *   <tr><td>{@code true}（<b>将来</b>）</td><td>XXL-Job 调度中心</td>
 *       <td>{@code XxlJobConfig} 的执行器；<b>本类不装配</b></td></tr>
 * </table>
 * <p>本类与 {@code common/config/XxlJobConfig} 的 {@code @ConditionalOnProperty}
 * <b>互为镜像</b>（那边 {@code havingValue = "true"}，这边 {@code "false"} +
 * {@code matchIfMissing = true}）。
 *
 * <p><b>为什么必须靠"只装配一个"来保证互斥，而不是靠"记得别同时开"</b>：
 * 两条路径同时生效<b>不会报错</b> —— 任务只是每天跑两遍。而这些 Job 恰好都是幂等的
 * （脱敏靠 {@code anonymized_at IS NULL} 收口，清理靠 {@code deleted_at} 收口，
 * <b>巡检压根不写库</b>），所以<b>真跑两遍也看不出来</b>。这正是本项目定义的头号故障形态，
 * 故互斥性由 {@code ScheduledJobTriggerConditionTest} 用 Spring 上下文<b>实测</b>钉住，
 * 不靠"读代码觉得互斥"。
 *
 * <h2>为什么落在 {@code job/} 而不是 {@code common/config/}</h2>
 * <p>本类<b>引用具体的 Job</b>（含 {@code org} 域的 {@code AnonymizeArchivedStudentJob}）。
 * 放 {@code common/config/} 会让公共层反向依赖业务域 —— 那是分层倒置，
 * 而 {@code check_backend_conventions.sh} 检查③ 只遍历八个领域包、<b>不检查
 * 从 {@code common} 出去的 import</b>，所以它拦不住我，这一处只能靠自觉。
 * {@code XxlJobConfig} 留在 {@code common/config/} 是对的：它只组装执行器，不认识任何 Job。
 *
 * <p>而 {@code job/} 本来就是「全部 XXL-Job 任务与 Worker」的所在（05-工程结构.md §H），
 * 且 §H 理由 3 明写「有的 Job 天然跨领域」—— 触发器与被触发者放在一起，
 * 也让「谁在触发什么」可以对着一个目录看完。
 *
 * <h2>委派 {@code run()}，不碰 {@code execute()}</h2>
 * <p>{@code execute()} 是 {@code @XxlJob} 的薄壳，{@code run()} 才是逻辑，
 * 且两个 Job 的 {@code run()} 的 Javadoc 本来就写着「供测试直接调用（不经 XXL-Job 调度器）」。
 * 走 {@code execute()} 会变成「Spring 调度触发 XXL-Job 薄壳」这种将来一定有人看不懂的路径。
 *
 * <p><b>顺带一个硬约束</b>：这些 {@code run()} 都有返回值（{@code CleanupSummary} / {@code int}），
 * 而 {@code @Scheduled} 要求 <b>void 且无参</b> —— 所以本类的包装方法不是多余的一层，
 * 是<b>必需的</b>。这也是「不要在 {@code run()} 上直接加 {@code @Scheduled}」的技术原因之一。
 *
 * <h2>⚠ {@code @Profile("!test")}：不能在集成测试期间被触发</h2>
 * <p>{@code src/test/resources/application-test.yml} 里逐字写着 {@code xxl.job.enabled: false}，
 * 所以<b>反向门控在测试期间是满足的</b>。若不额外排除，一旦某条 cron 在测试运行时刚好命中：
 * 清理任务会去删测试数据、脱敏任务会去改测试数据，而<b>表现是随机的偶发失败、过后极难复现</b>。
 *
 * <p><b>选 {@code @Profile("!test")} 而不是另加一个开关</b>，两条理由：
 * <ol>
 *   <li><b>不新增配置项</b>。{@code 05-工程结构.md} §F4 是部署级参数的登记册，
 *       加一个键就要同步 §F4 与 {@code deploy/.env.example}，并且它从此是一个
 *       运维<b>可以配错</b>的东西。profile 门控的配置面是零；</li>
 *   <li><b>生产不可能误命中</b>。{@code edumatrix.service} 里是
 *       {@code --spring.profiles.active=prod}，{@code !test} 天然满足；
 *       要在生产关掉它得把生产 profile 命名成 {@code test}。</li>
 * </ol>
 * <p><b>残留风险，如实写在这里</b>：这条门控依赖「每个 IT 都激活了 {@code test} profile」。
 * 全库的 IT 都走 {@code support/IntegrationTest}，而它<b>硬编码</b>了
 * {@code @ActiveProfiles("test")} —— {@code ScheduledJobTriggerConditionTest} 里有一条
 * 断言把这件事钉住，删掉那个注解会红。若将来有人写一个不走 {@code @IntegrationTest}
 * 又不设 profile 的 Spring 测试，本门控对它无效。
 *
 * <h2>转向时机</h2>
 * <p>XXL-Job 真正用得上的是「执行历史 + 手动补跑」，而那两条要到<b>模块 13</b>
 * （心跳落盘每 60 秒一次、数据量大、失败必须能看见能补跑）才从「有更好」变成「必须有」。
 * 在那之前为一个单实例（契约 §J1）搭一整套调度中心不划算。
 *
 * <p><b>切换时 cron 必须与调度中心的登记值逐字一致</b>，否则行为会在切换那一刻变 ——
 * 两个值就在下面两个常量上，登记时照抄。
 */
@Configuration
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "false", matchIfMissing = true)
public class ScheduledJobTrigger {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobTrigger.class);

    /**
     * 删除请求脱敏，<b>每日 02:30</b>。将来在调度中心登记时<b>照抄本值</b>。
     *
     * <p>{@code AnonymizeArchivedStudentJob} 的 Javadoc 与 05-工程结构.md §H 只写了
     * 「每日一次」，<b>没有给具体时刻</b>，故本值由模块 05 定，依据是与另两个日任务错开：
     * {@code DailySettleJob} 00:30（§H）→ 本任务 02:30 → {@code TempFileCleanupJob} 03:30。
     * 各隔一小时，一个跑久了也不会追上下一个（两个日任务共用的
     * {@code taskScheduler} 只有 1 个线程，串行执行 —— 这在两个日任务的量级上是合适的，
     * 但别再往里塞第三个高频任务）。
     *
     * <p><b>模块 09 的消费任务正是那个「第三个高频任务」，处置是给它一个独立的调度器
     * 而不是塞进这个池</b>：见 {@link #FIXED_DELAY_VOD_EVENT_CONSUME} 与
     * {@code common/config/SchedulerConfig}。
     *
     * <p>承载的是契约 §7.2 第 3 条 / 《个保法》第 31 条的 <b>30 日不可逆脱敏</b>。
     */
    public static final String CRON_ANONYMIZE_ARCHIVED_STUDENT = "0 30 2 * * *";

    /**
     * 敏感文件 7 天保留期物理清理，<b>每日 03:30</b>。将来在调度中心登记时<b>照抄本值</b>。
     *
     * <p>取值来自 {@code TempFileCleanupJob} 的 Javadoc（Q-2 定案：「触发 <b>每日 03:30</b>
     * （避开模块 16 {@code DailySettleJob} 的 00:30）」）。
     *
     * <p>承载的是 {@code 00-通用约定} §7.4 第 6 条，其中 {@code credential_sheet}
     * <b>含明文初始密码</b>（03-01 §7.3）。
     */
    public static final String CRON_TEMP_FILE_CLEANUP = "0 30 3 * * *";

    /**
     * 转码事件消费的触发间隔，<b>10 秒</b>（模块 09；03-03 §7.2、04 §B 模块 09 对外产出）。
     *
     * <h2>为什么是 {@code fixedDelay} 而不是 cron</h2>
     * <p>{@code fixedDelay} 是<b>上一轮跑完之后</b>再等 10s：天然背压，云端慢的时候自动降频，
     * 且永远不会有两轮重叠。cron 是固定节拍，上一轮没跑完时下一轮照样到点排队。
     *
     * <p><b>代价必须写下来</b>：切换到调度中心时登记的是 cron {@code 0/10 * * * * ?}，
     * 与本值<b>语义不同</b> —— 那不是「照抄」而是「换语义」。已登记 <b>F-68</b>，
     * {@code VodEventConsumeJob} 的类注释里有同一张表。
     *
     * <h2>它跑在 {@code vodEventTaskScheduler} 上，不在上面那个池里</h2>
     * <p>{@code @Scheduled(scheduler = ...)} 显式绑定（Spring Framework 6.1 起支持）。
     * 理由与「一个卡死时另外两个还跑不跑」的证明见 {@code common/config/SchedulerConfig}。
     */
    public static final String FIXED_DELAY_VOD_EVENT_CONSUME = "10000";

    /**
     * 授权健康度巡检，<b>每日 04:30</b>（模块 11）。将来在调度中心登记时<b>照抄本值</b>。
     *
     * <h2>为什么是 04:30（F-90）</h2>
     * <p>03-02 §9.6 只写「巡检每日低峰按租户分批执行」，<b>没有给时刻</b>；
     * 该节响应示例里的 {@code detectedTime} 是 {@code 03:10:00}，
     * 但那是<b>示例值不是规范</b>。取 04:30 是延续既有纪律：
     * {@code DailySettleJob} 00:30 → {@code anonymizeArchivedStudent} 02:30 →
     * {@code tempFileCleanup} 03:30 → <b>本任务 04:30</b>，各错开一小时。
     *
     * <p>三个日任务共用 {@code taskScheduler}（<b>1 个线程，串行</b>），
     * 一个跑久了会推迟下一个 —— 错开一小时是为此留的余量。
     * <b>不为它新增第三个调度器 Bean</b>：那会让
     * {@code SchedulerConfig} 里「默认池必须显式存在」的那条隔离断言重新变脆
     *（Boot 的 {@code TaskSchedulerConfiguration} 挂 {@code @ConditionalOnMissingBean}，
     * 多一个 {@code TaskScheduler} 就会让默认的整个退避）。巡检是<b>日</b>任务，
     * 不是模块 09 那种 10 秒一轮的高频任务，进默认池是合适的。
     */
    public static final String CRON_GRANT_CONSISTENCY = "0 30 4 * * *";

    private final AnonymizeArchivedStudentJob anonymizeJob;
    private final TempFileCleanupJob tempFileCleanupJob;
    private final VodEventConsumeJob vodEventConsumeJob;
    private final GrantConsistencyJob grantConsistencyJob;

    public ScheduledJobTrigger(AnonymizeArchivedStudentJob anonymizeJob,
                               TempFileCleanupJob tempFileCleanupJob,
                               VodEventConsumeJob vodEventConsumeJob,
                               GrantConsistencyJob grantConsistencyJob) {
        this.anonymizeJob = anonymizeJob;
        this.tempFileCleanupJob = tempFileCleanupJob;
        this.vodEventConsumeJob = vodEventConsumeJob;
        this.grantConsistencyJob = grantConsistencyJob;
        // 「谁在触发」必须一眼看见，而不是靠读配置推断 —— 与 OssClient 那行
        // 「对象存储 = …」同一个用途。XXL-Job 那条路径生效时，
        // XxlJobConfig 会打它自己的那一行，两条互斥所以日志里只会出现一条
        log.info("定时触发 = Spring 调度（过渡期，xxl.job.enabled=false；调度中心未部署，见 F-41）"
                        + "｜日任务[{}]：anonymizeArchivedStudent[{}]、tempFileCleanup[{}]"
                        + "、grantConsistency[{}]｜高频任务[{}]：vodEventConsume[fixedDelay {}ms]",
                SchedulerConfig.DEFAULT_SCHEDULER,
                CRON_ANONYMIZE_ARCHIVED_STUDENT, CRON_TEMP_FILE_CLEANUP, CRON_GRANT_CONSISTENCY,
                SchedulerConfig.VOD_EVENT_SCHEDULER, FIXED_DELAY_VOD_EVENT_CONSUME);
    }

    /**
     * 删除请求脱敏（PRD F7-3 / 契约 §7.2 第 3 条）。
     *
     * <p>调 {@code run()} 而不是 {@code execute()}：见类注释。
     */
    @Scheduled(cron = CRON_ANONYMIZE_ARCHIVED_STUDENT)
    public void triggerAnonymizeArchivedStudent() {
        runQuietly("anonymizeArchivedStudent", () -> anonymizeJob.run());
    }

    /** 敏感文件 7 天保留期清理（{@code 00-通用约定} §7.4 第 6 条）。 */
    @Scheduled(cron = CRON_TEMP_FILE_CLEANUP)
    public void triggerTempFileCleanup() {
        runQuietly("tempFileCleanup", () -> tempFileCleanupJob.run());
    }

    /**
     * 授权健康度巡检（PRD FR-7、契约 §2.5 规则 6）。
     *
     * <p><b>本方法与 {@code GrantConsistencyJob#execute()} 上的
     * {@code @XxlJob("grantConsistency")} 必须同时存在</b> ——
     * 两条触发路径互斥、任何时刻只生效一条，<b>只登记一边的后果是：
     * 切换到调度中心的那一刻，这个任务静默消失</b>，
     * 而「悬挂授权没人巡检」不会有任何东西报错。
     */
    @Scheduled(cron = CRON_GRANT_CONSISTENCY)
    public void triggerGrantConsistency() {
        runQuietly("grantConsistency", () -> grantConsistencyJob.run());
    }

    /**
     * 转码事件消费（03-03 §7.2）。<b>跑在独立的 {@code vodEventTaskScheduler} 上</b> ——
     * 与上面两个合规日任务互不阻塞，理由见 {@code common/config/SchedulerConfig}。
     */
    @Scheduled(fixedDelayString = FIXED_DELAY_VOD_EVENT_CONSUME,
            scheduler = SchedulerConfig.VOD_EVENT_SCHEDULER)
    public void triggerVodEventConsume() {
        runQuietly("vodEventConsume", () -> vodEventConsumeJob.run());
    }

    /**
     * 吞掉异常并记 ERROR。
     *
     * <p>Spring 的调度器本来也会记下未捕获异常并<b>继续下一次触发</b>，
     * 所以这里包一层不是为了防止调度停摆，而是为了<b>让日志里那条消息是我们自己的</b>：
     * 带任务名、说清「本次失败不影响下一次」。否则出现的是一条框架栈，
     * 排查的人第一反应会是"调度坏了"而不是"这个任务本次失败了"。
     */
    private static void runQuietly(String handler, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Spring 调度触发的任务 {} 本次执行失败（不影响下一次触发；"
                    + "失败项的收口标记未写，下次调度会重扫）", handler, e);
        }
    }
}
