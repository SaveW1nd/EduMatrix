package com.edumatrix.job;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.edumatrix.common.file.FileConstants;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.system.file.service.TempFileCleanupService;

import com.xxl.job.core.handler.annotation.XxlJob;

/**
 * 敏感文件 7 天保留期物理清理（{@code 00-通用约定} §7.4 末行、PRD F4-4 规则 3、03-05 §4.8）。
 *
 * <p><b>05-工程结构.md 的 Q-2 在模块 05 落地时定案</b>：类名沿用 §H 的占位名
 * （改名要同步 §H / §D / Q-2 三处，收益为零）；位置 {@code com.edumatrix.job}（§H 的定案位置）；
 * 触发 <b>每日 03:30</b>（避开模块 16 {@code DailySettleJob} 的 00:30）；归模块 05 交付。
 *
 * <h2>⚠ 与模块 07 脱敏任务的边界靠三层，每层都能独立失败</h2>
 * <table border="1">
 *   <caption>三层防线</caption>
 *   <tr><th>层</th><th>机制</th><th>被绕过时会失败吗</th></tr>
 *   <tr><td>L1</td><td>本类只注入 {@link TempFileCleanupService}，
 *       <b>不注入任何日志表 Mapper</b></td>
 *       <td><b>编译期</b>。想删日志表得先加一个注入，那是一次显式改动</td></tr>
 *   <tr><td>L2</td><td>清理条件是<b>正向白名单</b>（{@code biz_type IN (...)}）</td>
 *       <td>新增 bizType 默认<b>不清理</b>。黑名单写法下，模块 08 加一个
 *           {@code course_cover} 就会让全部课程封面在 7 天后被静默删掉 ——
 *           表现是课程列表的图全变裂图，而没有任何一处报错</td></tr>
 *   <tr><td>L3</td><td>先删对象、成功后才写 {@code deleted_at}</td>
 *       <td>顺序反了会留下<b>删不掉的 OSS 孤儿对象</b>：库里已删，没人知道 key</td></tr>
 * </table>
 *
 * <p><b>核实过（模块 05 落地时逐条验的）</b>：模块 07 的
 * {@code AnonymizeArchivedStudentJob} / {@code StudentAnonymizeService} <b>不碰</b>两张日志表 ——
 * 它只 UPDATE {@code org_student} 与 {@code sys_user}，并<b>往</b> {@code sys_oper_log} 写一行留痕；
 * 那条写入路径的 Mapper（现已并入 {@code common/operlog/mapper/OperLogMapper}）
 * <b>只有 INSERT、没有 DELETE</b>，是结构性的，不靠注释保证。
 *
 * <h2>⚠ 本类证明不了「保留 ≥ 6 个月」</h2>
 * <p>{@code TempFileCleanupJobIT} 的 T-9 能证明的只有「<b>本 Job</b> 跑完之后
 * 那两张表一行不少」。而契约 §7.2 第 5 条要求的保留期本身<b>全库没有任何承载</b> ——
 * 没有归档任务、没有保留期检查，也没有任何东西会在有人手工
 * {@code DELETE FROM sys_oper_log} 时失败（03-01 §8 引言逐字「归档清理为运维行为」）。
 * <b>已登记 F-33。</b>
 *
 * <h2>逐租户进入，不用 {@code ignore()}</h2>
 * <p>照抄 {@code AnonymizeArchivedStudentJob} 的做法与理由：{@code ignore()} 是逃生舱，
 * 每新增一处都要能说清「为什么这个查询<b>非跨租户不可</b>」；
 * 而本任务并不需要跨租户<b>查询</b>，只需要<b>依次进入</b>每个租户 ——
 * 这两件事形似而不同。全库的 {@code ignore()} 调用点仍然只有 1 处。
 *
 * <h2>⚠ 谁在触发它：两条路径<b>互斥</b>，靠 {@code xxl.job.enabled} 切换</h2>
 * <table border="1">
 *   <caption>任何时刻只有一条存在</caption>
 *   <tr><th>{@code xxl.job.enabled}</th><th>触发者</th></tr>
 *   <tr><td>{@code false} 或未配（<b>现状</b>）</td>
 *       <td>{@code job/ScheduledJobTrigger}（Spring 调度，过渡期；需方定案暂不部署调度中心，见 F-41）</td></tr>
 *   <tr><td>{@code true}（<b>将来</b>）</td>
 *       <td>XXL-Job 调度中心，经 {@link #execute()} 上的 {@code @XxlJob("tempFileCleanup")}</td></tr>
 * </table>
 * <p><b>切换时 cron 必须与调度中心的登记值逐字一致</b>，否则行为会在切换那一刻变。
 * 过渡期的值在 {@code ScheduledJobTrigger#CRON_TEMP_FILE_CLEANUP}
 * （{@code 0 30 3 * * *}，即本类定的每日 03:30），登记时照抄。
 *
 * <p>互斥由两个 {@code @ConditionalOnProperty} 互为镜像保证，并由
 * {@code ScheduledJobTriggerConditionTest} 实测钉住 ——
 * <b>双触发不会报错</b>，任务只是每天跑两遍，而本任务幂等（靠 {@code deleted_at} 收口），
 * 所以真跑两遍也看不出来。
 *
 * <h2>逐个文件一个事务，单个失败不拖垮整批</h2>
 * <p>与建人的「三写一事务」不同：那是一个业务动作的原子性，这是一批互相独立的动作。
 * 一个文件删失败不该让另外 999 个也不删；失败的那个 {@code deleted_at} 仍为 0，下次重扫。
 */
@Component
public class TempFileCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TempFileCleanupJob.class);

    /** 单租户单次扫描上限。与 {@code AnonymizeArchivedStudentJob} 同值同理由。 */
    private static final int BATCH_LIMIT = 1000;

    private final TempFileCleanupService cleanupService;

    public TempFileCleanupJob(TempFileCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /** 每日 03:30（调度周期在 XXL-Job 后台配置，此处只声明 handler 名）。 */
    @XxlJob("tempFileCleanup")
    public void execute() {
        run();
    }

    /** 与 {@link #execute()} 分开，供测试直接调用（不经 XXL-Job 调度器）。 */
    public CleanupSummary run() {
        LocalDateTime deadline = LocalDateTime.now().minus(FileConstants.TEMP_FILE_RETENTION);
        int deleted = 0;
        int failed = 0;

        for (Long tenantId : cleanupService.activeTenantIds()) {
            List<TempFileCleanupService.CleanupCandidate> candidates =
                    TenantHelper.runWithTenant(tenantId,
                            () -> cleanupService.findExpired(deadline, BATCH_LIMIT));
            for (TempFileCleanupService.CleanupCandidate candidate : candidates) {
                boolean ok = TenantHelper.runWithTenant(tenantId, () -> cleanupService.purge(candidate));
                if (ok) {
                    deleted++;
                } else {
                    failed++;
                }
            }
        }

        log.info("敏感文件清理完成：deadline={} 已清理={} 失败={}（失败项 deleted_at 仍为 0，下次重扫）",
                deadline, deleted, failed);
        return new CleanupSummary(deleted, failed);
    }

    /** 一次运行的结果，供测试与运维核对。 */
    public record CleanupSummary(int deleted, int failed) {
    }
}
