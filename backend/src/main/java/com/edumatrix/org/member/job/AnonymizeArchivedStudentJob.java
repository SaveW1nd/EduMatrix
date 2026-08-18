package com.edumatrix.org.member.job;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.member.entity.OrgStudent;
import com.edumatrix.org.member.service.StudentAnonymizeService;

import com.xxl.job.core.handler.annotation.XxlJob;

/**
 * 删除请求脱敏任务（04-实施计划.md 模块 07「对外产出」，XXL-Job，每日一次）。
 *
 * <p>PRD F7-3 的路径：<b>因删除请求归档 → 30 日撤回窗口 → 不可逆脱敏</b>。
 * 本类只做「进入每个租户、找出该脱的人、逐个交给 Service」，脱敏本身在
 * {@code StudentAnonymizeService#anonymize}。
 *
 * <h2>扫描条件是<b>三个与门</b>，SQL 在 {@code OrgStudentMapper#selectAnonymizeCandidates}</h2>
 * <pre>
 * archive_reason = 2            ← 少了它会误脱敏【毕业校友】，而那不可逆
 * archive_time  &lt;= NOW() - 30d  ← 少了它会脱掉还在撤回窗口内的
 * anonymized_at IS NULL         ← 少了它会重复脱敏
 * </pre>
 * <p><b>第一个条件是最容易漏、且漏了不会有任何东西报错的那个</b>：
 * 只测「{@code reason=2} 满 30 日会脱敏」是测不出来的，必须有一条
 * 「{@code reason=1} 满 30 日<b>不</b>被脱敏」的对照用例
 * （{@code StudentAnonymizeIT#graduatedStudentIsNotAnonymizedAfterThirtyDays}）。
 *
 * <h2>无会话：<b>逐租户</b>进入，不用 {@code ignore()}</h2>
 * <p>契约 §2.8 规则 1「从数据显式取」。做法是：先取租户清单
 * （{@code sys_tenant} 不带 {@code tenant_id} 列、压根不进插件，因此无需任何逃生舱），
 * 再<b>逐个 {@code runWithTenant(tenantId, …)} 包住扫描与写入</b>。
 *
 * <p><b>刻意不用 {@code TenantHelper.ignore()}</b>：它是逃生舱，每新增一处都要能说清
 * 「为什么这个查询<b>非跨租户不可</b>」（{@code check_backend_conventions.sh} 检查④ 的清单）。
 * 而本任务并不需要跨租户<b>查询</b> —— 它只是需要<b>依次进入</b>每个租户，
 * 这两件事形似而不同。逐租户方案的副作用是每天多 N 条扫描 SQL（N = 租户数），
 * 对一个每日一次的任务可以忽略。
 *
 * <h2>逐人一个事务，单个失败不拖垮整批</h2>
 * <p>与建人的「三写一事务」不同：那是<b>一个业务动作的原子性</b>，
 * 这是<b>一批互相独立的动作</b>。一个人脱敏失败不该让另外 99 个也不脱。
 * 失败的人 {@code anonymized_at} 仍为 {@code NULL}，下次调度会重扫到。
 */
@Component
public class AnonymizeArchivedStudentJob {

    private static final Logger log = LoggerFactory.getLogger(AnonymizeArchivedStudentJob.class);

    /** 单租户单次扫描上限。够一天的量，又不至于让一次异常积压把内存吃满。 */
    private static final int BATCH_LIMIT = 1000;

    private final StudentAnonymizeService anonymizeService;

    public AnonymizeArchivedStudentJob(StudentAnonymizeService anonymizeService) {
        this.anonymizeService = anonymizeService;
    }

    @XxlJob("anonymizeArchivedStudent")
    public void execute() {
        run();
    }

    /**
     * 与 {@link #execute()} 分开，供测试直接调用（不经 XXL-Job 调度器）。
     *
     * @return 本次实际脱敏的人数
     */
    public int run() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(OrgStudent.ANONYMIZE_DELAY_DAYS);
        List<Long> tenantIds = anonymizeService.findActiveTenantIds();

        int done = 0;
        for (Long tenantId : tenantIds) {
            done += runForTenant(tenantId, deadline);
        }
        log.info("删除请求脱敏任务完成：{} 个租户，脱敏 {} 人（扫描条件 archive_reason=2 "
                        + "AND archive_time <= {} AND anonymized_at IS NULL）",
                tenantIds.size(), done, deadline);
        return done;
    }

    private int runForTenant(Long tenantId, LocalDateTime deadline) {
        // 【租户显式取自数据】契约 §2.8 规则 1。靠线程残留会把写入落到上一个租户名下，
        // 而且不报错 —— TenantHelper 类注释里那句「不清理就是下一个任务串租户」
        List<OrgStudent> candidates = TenantHelper.runWithTenant(tenantId,
                () -> anonymizeService.findCandidates(deadline, BATCH_LIMIT));

        int done = 0;
        for (OrgStudent student : candidates) {
            try {
                TenantHelper.runWithTenant(tenantId, () -> anonymizeService.anonymize(student));
                done++;
            } catch (RuntimeException e) {
                // 单人失败不拖垮整批。ERROR 级：这是对监护人的删除承诺没兑现，
                // 下次调度会重扫到他（anonymized_at 仍为 NULL），但必须有人看见
                log.error("脱敏失败，studentId={} tenantId={}（下次调度会重扫到，"
                        + "但连续失败意味着删除承诺未兑现）", student.getId(), tenantId, e);
            }
        }
        return done;
    }
}
