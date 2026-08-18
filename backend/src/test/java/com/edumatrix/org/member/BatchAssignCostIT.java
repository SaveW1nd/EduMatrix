package com.edumatrix.org.member;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.member.dto.AssignTeacherBatchReq;
import com.edumatrix.org.member.service.StudentAssignService;
import com.edumatrix.org.member.support.MemberFixtures;
import com.edumatrix.org.member.support.MemberIntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>批量分配导师的事务代价实测</b>（工单完成判据第 5 条）。
 *
 * <p>工单原文：「跑一次批量分配（数量你定），记录<b>事务持有时长</b>与<b>实际加锁行数</b>，
 * 贴出来。若代价不可接受，告诉我，<b>不要自行改设计</b>——拆成多个事务会破坏
 * 『整批成功或整批回滚』，那是规则 6 明令禁止的」。
 *
 * <h2>为什么这个用例存在，而不是靠算</h2>
 * <p>{@code NodeMoveService#lockIds} 的加锁集合是「被移动节点 ∪ 旧父及祖先链 ∪ 新父及祖先链」，
 * 算出来是<b>每次 move 个位数行</b>；但批量是同一个事务里连着调 n 次，
 * <b>锁只加不放（直到提交）</b>，累积量与祖先链的重叠程度有关 —— 算不准，只能测。
 *
 * <h2>怎么测到「实际加锁行数」</h2>
 * <p>{@code information_schema.innodb_trx.trx_rows_locked} 是 InnoDB 自己报的
 * 「本事务当前锁住的行数」。<b>必须在事务提交之前、且在同一个连接上查</b> ——
 * 提交后这一行就从 {@code innodb_trx} 里消失了。
 * 所以本用例用 {@code TransactionTemplate} 手动持有事务，在回调<b>内部</b>查。
 *
 * <p>用 {@code TestCurrentContextProvider} 直接设会话（而不是走登录）：
 * 本用例要的是<b>一个可控的事务边界</b>，走 MockMvc 的话事务在 Service 内部开合，
 * 拿不到「提交前」那一刻。会话本身不是本用例的判据。
 */
class BatchAssignCostIT extends MemberIntegrationTestBase {

    @Autowired
    private StudentAssignService assignService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("实测：12 人批量分配的事务持有时长与实际加锁行数")
    void measureBatchAssignCost() {
        List<Long> profileIds = new ArrayList<>();
        for (long studentNodeId : MemberFixtures.STUDENTS) {
            profileIds.add(MemberFixtures.profileIdOf(studentNodeId));
        }

        AssignTeacherBatchReq req = new AssignTeacherBatchReq();
        req.setStudentIds(profileIds);
        req.setToTeacherNodeId(MemberFixtures.T2);
        req.setReason("事务代价实测");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long startedAt = System.nanoTime();

        Map<String, Object> metrics = withRootSession(() ->
                tx.execute(status -> {
                    assignService.assignBatch(req);
                    // 【必须在提交前查】提交后这一行就从 innodb_trx 里消失了
                    return jdbcTemplate.queryForMap(
                            "SELECT trx_rows_locked, trx_lock_structs, trx_rows_modified "
                                    + "FROM information_schema.innodb_trx "
                                    + "WHERE trx_mysql_thread_id = CONNECTION_ID()");
                }));

        long heldMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        long rowsLocked = ((Number) metrics.get("trx_rows_locked")).longValue();
        long lockStructs = ((Number) metrics.get("trx_lock_structs")).longValue();
        long rowsModified = ((Number) metrics.get("trx_rows_modified")).longValue();

        System.out.printf(
                "%n[批量分配事务代价实测] 学员数=%d｜事务持有=%d ms｜"
                        + "trx_rows_locked=%d｜trx_lock_structs=%d｜trx_rows_modified=%d%n",
                MemberFixtures.STUDENT_COUNT, heldMillis, rowsLocked, lockStructs, rowsModified);

        // 结果确实生效了（不是空跑一遍测出来的 0）
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T2))
                .isEqualTo(MemberFixtures.STUDENT_COUNT);
        assertThat(rowsLocked).isPositive();
        assertThat(rowsModified).isPositive();
    }

    @Test
    @DisplayName("加锁顺序：名单顺序颠倒后，加锁集合与行数一致（按 node_id 升序遍历生效）")
    void lockOrderIsIndependentOfRequestOrder() {
        List<Long> ascending = new ArrayList<>();
        for (long studentNodeId : MemberFixtures.STUDENTS) {
            ascending.add(MemberFixtures.profileIdOf(studentNodeId));
        }
        List<Long> descending = new ArrayList<>(ascending);
        java.util.Collections.reverse(descending);

        long lockedAsc = runAndMeasureLockedRows(ascending);
        memberFixtures.clean();
        memberFixtures.seed();
        long lockedDesc = runAndMeasureLockedRows(descending);

        // 【判据】两次的加锁行数相同 —— 说明遍历顺序由 node_id 决定，与请求体顺序无关。
        // 若实现按请求体顺序遍历，两个并发批量事务名单相反时就是一个 AB-BA 死锁，
        // 而 NodeMoveService 内部的 id 升序加锁【一点忙都帮不上】（它只管单次 move）
        System.out.printf("[加锁顺序] 升序名单 rows_locked=%d｜降序名单 rows_locked=%d%n",
                lockedAsc, lockedDesc);
        assertThat(lockedDesc).isEqualTo(lockedAsc);
    }

    @Test
    @DisplayName("实测：500 人批量分配（分册上限）的事务持有时长与实际加锁行数")
    void measureFiveHundredBatchAssignCost() {
        // 【先热身】第一次调用含 JIT 与连接池预热，直接测会把那部分算进事务持有时长。
        // 热身用另一位教师的既有 12 人，与被测的 500 人互不影响
        List<Long> warmUp = new ArrayList<>();
        for (long studentNodeId : MemberFixtures.STUDENTS) {
            warmUp.add(MemberFixtures.profileIdOf(studentNodeId));
        }
        runAndMeasureLockedRows(warmUp);
        memberFixtures.clean();
        memberFixtures.seed();

        int count = 500;   // 03-02 §6.6 的单次上限
        List<Long> profileIds = memberFixtures.seedStudents(MemberFixtures.T1, count);

        AssignTeacherBatchReq req = new AssignTeacherBatchReq();
        req.setStudentIds(profileIds);
        req.setToTeacherNodeId(MemberFixtures.T2);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long startedAt = System.nanoTime();
        Map<String, Object> metrics = withRootSession(() -> tx.execute(status -> {
            assignService.assignBatch(req);
            return jdbcTemplate.queryForMap(
                    "SELECT trx_rows_locked, trx_lock_structs, trx_rows_modified "
                            + "FROM information_schema.innodb_trx "
                            + "WHERE trx_mysql_thread_id = CONNECTION_ID()");
        }));
        long heldMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("%n[批量分配事务代价实测·500 人] 事务持有=%d ms｜"
                        + "trx_rows_locked=%d｜trx_lock_structs=%d｜trx_rows_modified=%d%n",
                heldMillis, ((Number) metrics.get("trx_rows_locked")).longValue(),
                ((Number) metrics.get("trx_lock_structs")).longValue(),
                ((Number) metrics.get("trx_rows_modified")).longValue());

        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T2)).isEqualTo(count);
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT);
    }

    /**
     * 用测试 provider 设一个机构最高管理员会话。
     *
     * <p>底座在 {@code @BeforeEach} 里把静态门面切到了真实的 Sa-Token provider
     * （本模块其余用例全部走真实登录）；本用例要的是<b>可控的事务边界</b>，
     * 走 MockMvc 拿不到「提交前」那一刻，所以这里临时切回测试 provider。
     * 底座的 {@code @AfterEach} 会把它切回去并清掉会话，不影响别的用例。
     */
    private <T> T withRootSession(java.util.function.Supplier<T> action) {
        TenantHelper.setProvider(testContextProvider);
        testContextProvider.asTenantUser(MemberFixtures.TENANT_ID,
                MemberFixtures.userIdOf(MemberFixtures.ROOT), MemberFixtures.ROOT);
        try {
            return action.get();
        } finally {
            testContextProvider.asNoSession();
            TenantHelper.setProvider(saTokenContextProvider);
        }
    }

    private long runAndMeasureLockedRows(List<Long> profileIds) {
        AssignTeacherBatchReq req = new AssignTeacherBatchReq();
        req.setStudentIds(profileIds);
        req.setToTeacherNodeId(MemberFixtures.T2);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Number locked = withRootSession(() -> tx.execute(status -> {
            assignService.assignBatch(req);
            return jdbcTemplate.queryForObject(
                    "SELECT trx_rows_locked FROM information_schema.innodb_trx "
                            + "WHERE trx_mysql_thread_id = CONNECTION_ID()", Number.class);
        }));
        return locked == null ? 0 : locked.longValue();
    }
}
