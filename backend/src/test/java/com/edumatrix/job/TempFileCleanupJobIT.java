package com.edumatrix.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.file.FileBizType;
import com.edumatrix.common.id.IdWorker;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestCurrentContextProvider;
import com.edumatrix.support.mapper.ProbeCleanupMapper;
import com.edumatrix.system.file.service.TempFileCleanupService;

/**
 * 敏感文件 7 天保留期清理（{@code 00-通用约定} §7.4 末行）—— T-7 ~ T-11。
 *
 * <h2>这一组的重点是 {@link #logTablesAreUntouched}（T-9）</h2>
 * <p>需方的问题是「你的 7 天清理任务怎么保证不误伤两张日志表，
 * 以及这个保证<b>怎么被测出来</b>」。用 grep 证明「Job 里没有删日志的 SQL」
 * 是弱的 —— 那只验了代码长什么样。T-9 验的是<b>行为</b>：
 * 造两条 400 天前的日志行，跑完 Job 之后<b>它们还在，且两张表的物理行数一行不少</b>。
 *
 * <h2>其余四条各守一件事</h2>
 * <ul>
 *   <li>T-7 该清的清掉（四个 bizType 各一行）；</li>
 *   <li><b>T-8 不该清的没动</b> —— 把白名单改成黑名单时只有它会红；</li>
 *   <li>T-10 边界：6 天前不清（把 {@code <} 写成 {@code <=} 或方向写反都会红）；</li>
 *   <li>T-11 对象删除失败时<b>不写 {@code deleted_at}</b>，下次重扫。</li>
 * </ul>
 */
@IntegrationTest
class TempFileCleanupJobIT {

    private static final long TENANT_A = 1953827104412590001L;
    private static final long ADMIN_USER_ID = 1953827104412590102L;
    private static final long ADMIN_NODE_ID = 1953827104412590001L;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private TempFileCleanupJob job;

    @Autowired
    private com.edumatrix.common.file.ObjectStorage objectStorage;

    @Autowired
    private ProbeCleanupMapper probe;

    @Autowired
    private TestCurrentContextProvider contextProvider;

    @BeforeEach
    void asOrgAdmin() {
        contextProvider.asTenantUser(TENANT_A, ADMIN_USER_ID, ADMIN_NODE_ID);
    }

    /** 造一行 {@code daysAgo} 天前的 {@code sys_file}，返回 fileId。 */
    private long seedFile(FileBizType bizType, int daysAgo) {
        long id = IdWorker.nextId();
        String key = bizType.code() + "/probe/" + id + ".xlsx";
        String createdAt = LocalDateTime.now().minusDays(daysAgo).format(TS);
        // storage 取【当前进程实际装配的那个】而不是写死 1 —— 写死的话，
        // 将来谁在测试里配了 OSS，这组用例会以"全部被跳过"的姿态诡异地绿
        TenantHelper.runWithTenant(TENANT_A, () -> probe.insertFileAt(
                id, "probe.xlsx", key, objectStorage.storageType(),
                bizType.code(), TENANT_A, createdAt));
        return id;
    }

    // ====================================================================
    // T-7 / T-8：该清的清、不该清的不动
    // ====================================================================

    @Test
    @DisplayName("T-7 四个敏感 bizType 超过 7 天后被清理（deleted_at != 0）")
    void expiredSensitiveFilesAreCleaned() {
        long importExcel = seedFile(FileBizType.IMPORT_EXCEL, 8);
        long failReport = seedFile(FileBizType.FAIL_REPORT, 8);
        long credentialSheet = seedFile(FileBizType.CREDENTIAL_SHEET, 8);
        long exportReport = seedFile(FileBizType.EXPORT_REPORT, 8);

        job.run();

        for (long id : new long[]{importExcel, failReport, credentialSheet, exportReport}) {
            assertThat(probe.selectDeletedAt(id)).as("fileId=%s", id).isNotNull().isNotZero();
        }
    }

    /**
     * <b>T-8</b>：把正向白名单改成 {@code NOT IN} 黑名单时，只有这一条会红。
     *
     * <p>黑名单写法下，模块 08 新增一个 {@code course_cover} 就会让全部课程封面
     * 在 7 天后被静默删掉 —— 表现是课程列表的图全变裂图，没有任何一处报错。
     */
    @Test
    @DisplayName("T-8 非敏感 bizType 即使超过 7 天也不清（白名单改黑名单则红）")
    void nonSensitiveFilesAreNeverCleaned() {
        long cover = seedFile(FileBizType.COURSE_COVER, 30);
        long attach = seedFile(FileBizType.MATERIAL_ATTACH, 30);
        long avatar = seedFile(FileBizType.AVATAR, 30);
        long answer = seedFile(FileBizType.ANSWER, 30);
        long common = seedFile(FileBizType.COMMON, 30);

        job.run();

        for (long id : new long[]{cover, attach, avatar, answer, common}) {
            assertThat(probe.selectDeletedAt(id))
                    .as("fileId=%s 不在 §7.4 点名的四个 bizType 里，不该被清理", id)
                    .isZero();
        }
    }

    @Test
    @DisplayName("清理范围恰好是 §7.4 + §7.3 点名的四个 bizType（多一个少一个都红）")
    void cleanupScopeIsExactlyTheFourNamedBizTypes() {
        assertThat(TempFileCleanupService.cleanupBizTypes())
                .containsExactlyInAnyOrder(
                        FileBizType.IMPORT_EXCEL.code(),
                        FileBizType.FAIL_REPORT.code(),
                        FileBizType.CREDENTIAL_SHEET.code(),
                        FileBizType.EXPORT_REPORT.code());
    }

    // ====================================================================
    // T-9：两张日志表一行不少 —— 需方点名要的那一条
    // ====================================================================

    @Test
    @DisplayName("T-9 跑完 Job 后 sys_login_log / sys_oper_log 一行不少（400 天前的行也还在）")
    void logTablesAreUntouched() {
        long oldLoginLogId = IdWorker.nextId();
        long oldOperLogId = IdWorker.nextId();
        String longAgo = LocalDateTime.now().minusDays(400).format(TS);

        TenantHelper.runWithTenant(TENANT_A, () -> {
            probe.insertLoginLogAt(oldLoginLogId, "probe-ghost", longAgo, TENANT_A);
            probe.insertOperLogAt(oldOperLogId, "探针清理", longAgo, TENANT_A);
        });

        AtomicLong loginBefore = new AtomicLong();
        AtomicLong operBefore = new AtomicLong();
        TenantHelper.runWithTenant(TENANT_A, () -> {
            loginBefore.set(probe.countLoginLogRows());
            operBefore.set(probe.countOperLogRows());
        });

        // 同时放几条超期的敏感文件，确保 Job 这一趟真的干了活
        seedFile(FileBizType.CREDENTIAL_SHEET, 8);
        TempFileCleanupJob.CleanupSummary summary = job.run();
        assertThat(summary.deleted()).as("Job 空转的话本用例就成了空转的绿灯").isPositive();

        TenantHelper.runWithTenant(TENANT_A, () -> {
            assertThat(probe.countLoginLogRows())
                    .as("契约 §7.2 第 5 条：两张日志表保留 ≥6 个月，且不参与删除请求的清理")
                    .isEqualTo(loginBefore.get());
            assertThat(probe.countOperLogRows()).isEqualTo(operBefore.get());
            assertThat(probe.loginLogExists(oldLoginLogId))
                    .as("400 天前的登录日志仍必须在 —— 它比任何保留期都老，最容易被顺手清掉")
                    .isEqualTo(1);
            assertThat(probe.operLogExists(oldOperLogId)).isEqualTo(1);
        });
    }

    // ====================================================================
    // T-10 / T-11：边界与失败路径
    // ====================================================================

    @Test
    @DisplayName("T-10 边界：6 天前的敏感文件不清（把 < 写成 <= 或方向写反都会红）")
    void filesWithinRetentionAreKept() {
        long sixDaysAgo = seedFile(FileBizType.IMPORT_EXCEL, 6);
        long eightDaysAgo = seedFile(FileBizType.IMPORT_EXCEL, 8);

        job.run();

        assertThat(probe.selectDeletedAt(sixDaysAgo))
                .as("保留期是 7 天，第 6 天还在保留期内")
                .isZero();
        assertThat(probe.selectDeletedAt(eightDaysAgo)).isNotZero();
    }

    /**
     * <b>T-11</b>：对象删不掉时<b>不写 {@code deleted_at}</b>。
     *
     * <p>本地存储下删一个不存在的文件不会抛异常（{@code deleteIfExists}），
     * 所以这里换个角度验同一条不变量：一行 {@code storage} 与当前存储实现不一致的记录
     * <b>必须被跳过</b>。两者防的是同一件事 —— <b>库里已删而对象还在</b>，
     * 那份含明文初始密码的账号密码表就永远留在桶里，而系统显示它"已清理"。
     */
    @Test
    @DisplayName("T-11 storage 与当前存储实现不一致的行被跳过，不写 deleted_at（防孤儿对象）")
    void rowsFromAnotherStorageBackendAreSkipped() {
        long id = IdWorker.nextId();
        String createdAt = LocalDateTime.now().minusDays(30).format(TS);
        // storage 指向【另一个】后端 —— 它的对象不在当前这个后端上
        int otherBackend = objectStorage.storageType() == 1 ? 2 : 1;
        TenantHelper.runWithTenant(TENANT_A, () -> probe.insertFileAt(
                id, "orphan.xlsx", "import_excel/probe/orphan.xlsx", otherBackend,
                FileBizType.IMPORT_EXCEL.code(), TENANT_A, createdAt));

        TempFileCleanupJob.CleanupSummary summary = job.run();

        assertThat(probe.selectDeletedAt(id))
                .as("删了库行会留下一个谁也找不到 key 的孤儿对象")
                .isZero();
        assertThat(summary.failed()).isPositive();
    }
}
