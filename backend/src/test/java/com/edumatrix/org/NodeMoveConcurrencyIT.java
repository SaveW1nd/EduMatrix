package com.edumatrix.org;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.node.service.NodeMoveOptions;
import com.edumatrix.org.node.service.NodeMoveService;
import com.edumatrix.org.support.OrgFixtures;
import com.edumatrix.org.support.OrgIntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>本模块的核心判据</b>（04-实施计划.md 模块 06「做完什么算做完」最后一条）：
 * 10 个并发事务交叉移动同一子树内的不同节点，跑完后全树 {@code ancestors} 与
 * {@code parent_id} 自洽、无环。
 *
 * <h2>怎么验「无环」</h2>
 * <p>用 02-数据库设计 §3.1.1 的<b>递归 CTE 巡检</b>：从租户根出发独立推导一遍真实祖级路径，
 * 与 {@code ancestors} 冗余列逐行比对。它同时是一个<b>成环探测器</b> ——
 * 遇环会被 {@code cte_max_recursion_depth} 截断报错（§3.1.4 末尾），
 * 那个异常会直接让本用例失败，不需要另写一段找环的代码。
 *
 * <h2>为什么直接调 Service 而不是发 10 个 HTTP 请求</h2>
 * <p>要验的是<b>事务与加锁顺序</b>，不是 MVC 分发。直接调 {@link NodeMoveService}
 * 让每个线程各开一个事务，竞争面恰好落在步骤 1 的 {@code FOR UPDATE} 上；
 * 走 MockMvc 则要额外假设它在并发下的行为，而那与本判据无关。
 *
 * <p>会话上下文改用模块 01 的 {@code TestCurrentContextProvider}：10 个线程是<b>同一个
 * 操作人</b>（机构最高管理员），provider 的字段是普通字段、线程间共享，正合此意。
 * 用例结束后底座的 {@code @AfterEach} 会把静态门面切回去。
 *
 * <h2>失败是预期结果的一部分</h2>
 * <p>交叉移动里必然有一部分撞上 {@code 10103} 成环、{@code 10205} 同父、{@code 10105}
 * 承载规则或行锁等待 —— <b>那正是要的</b>。判据不是「10 个全成功」，
 * 而是「无论谁成谁败，树都不能坏」。
 */
class NodeMoveConcurrencyIT extends OrgIntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(NodeMoveConcurrencyIT.class);

    /** 夹具树的节点总数（含租户根）。 */
    private static final int TOTAL_NODES = 15;

    @Autowired
    private NodeMoveService nodeMoveService;

    @Test
    @DisplayName("10 个并发事务交叉移动同一子树内的不同节点，跑完全树自洽、无环、无节点掉队")
    void concurrentCrossMovesKeepTheTreeConsistent() throws Exception {
        // 同一个操作人（机构最高管理员），10 个线程各开一个事务
        TenantHelper.setProvider(testContextProvider);
        testContextProvider.asTenantUser(
                OrgFixtures.TENANT_ID, OrgFixtures.userIdOf(OrgFixtures.ROOT), OrgFixtures.ROOT);

        // 交叉：既有学员在教师之间横跳，也有教师/管理员整支换上级，
        // 且 9/10 两条会与 1~8 抢同一批行（T1/T2 与它们底下的学员同时在动）。
        // F-114 之前第 10 条搬的是嵌套管理员 A3，现在树里没有嵌套管理员了
        long[][] moves = {
                {OrgFixtures.S1, OrgFixtures.T2},
                {OrgFixtures.S2, OrgFixtures.T3},
                {OrgFixtures.S3, OrgFixtures.TX},
                {OrgFixtures.S4, OrgFixtures.T1},
                {OrgFixtures.S5, OrgFixtures.T3},
                {OrgFixtures.S6, OrgFixtures.T1},
                {OrgFixtures.S7, OrgFixtures.T2},
                {OrgFixtures.S8, OrgFixtures.TX},
                {OrgFixtures.T1, OrgFixtures.A2},
                {OrgFixtures.T2, OrgFixtures.A2},
        };

        ExecutorService pool = Executors.newFixedThreadPool(moves.length);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(moves.length);
        AtomicInteger succeeded = new AtomicInteger();
        List<String> rejections = new ArrayList<>();

        try {
            for (long[] move : moves) {
                pool.submit(() -> {
                    try {
                        start.await();
                        nodeMoveService.move(move[0], move[1], NodeMoveOptions.none());
                        succeeded.incrementAndGet();
                    } catch (Exception e) {
                        // 成环 / 同父 / 承载规则 / 行锁等待超时都在预期内。
                        // 记错误码而不是 message：BizException 刻意不填栈也不带动态文案，
                        // 只有码能说明它是被哪一条校验拦下的
                        String reason = e instanceof BizException biz
                                ? "code=" + biz.getErrorCode().getCode() + " " + biz.getErrorCode().getMsg()
                                : e.getClass().getSimpleName() + " " + e.getMessage();
                        synchronized (rejections) {
                            rejections.add(move[0] + "→" + move[1] + " : " + reason);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("10 个并发移动应在 60 秒内全部收敛（未收敛 = 死锁未被 id 升序加锁挡住）")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        log.info("并发移动结果：成功 {} / {}，被拒 {} 条", succeeded.get(), moves.length, rejections.size());
        rejections.forEach(r -> log.info("  被拒：{}", r));

        // 判据①：递归 CTE 独立推导的路径与 ancestors 冗余列逐行一致（遇环这里会直接抛）
        assertThat(orgFixtures.auditTreeConsistency())
                .as("全树 ancestors 与 parent_id 必须自洽（§3.1.1 递归 CTE 巡检）")
                .isEmpty();

        // 判据②：从租户根出发能走到全部 15 个节点 —— 没有节点掉出树，也没有节点被数两遍
        assertThat(orgFixtures.reachableNodeCount())
                .as("全部节点仍应从租户根可达")
                .isEqualTo(TOTAL_NODES);

        // 判据③：至少有一次移动真的落库了，否则上面两条在一棵没动过的树上也成立
        assertThat(succeeded.get()).isPositive();

        // 判据④：【一次死锁都不许有】。04-实施计划.md §D 前置风险项 R2
        // 「撞车后的影响面」那一行逐字：
        // 「若 10 并发下出现死锁，说明 id 升序加锁没有覆盖全部加锁点——
        //   这属于铁律 1 未落地，不可上线」。
        // 这条断言是那句话的回归守卫：把 NodeMoveService#lockIds 缩回「只锁被移动节点 +
        // 目标父」（即 §3.1.3 模板的字面写法），本用例会立刻红 —— 实测 6/10 死锁，
        // 环里是 student_count 的祖先链 UPDATE 与旧父的 child_count UPDATE
        assertThat(rejections)
                .as("id 升序加锁必须覆盖本事务的全部点写入行，一次死锁都不该有")
                .noneMatch(r -> r.contains("Deadlock"));
    }
}
