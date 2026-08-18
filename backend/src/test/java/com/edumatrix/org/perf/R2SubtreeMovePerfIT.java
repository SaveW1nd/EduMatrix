package com.edumatrix.org.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.node.service.NodeMoveOptions;
import com.edumatrix.org.node.service.NodeMoveService;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestCurrentContextProvider;

/**
 * <b>R2 压测：{@code ancestors} 子树前缀重算在真实数据量下的耗时</b>
 * （04-实施计划.md §D 前置风险项 R2）。
 *
 * <p>R2 的「验证方式」栏逐字：「造 1.1 万节点、深度 6~8 层的真实形态树；分别移动
 * <b>100 / 1000 / 5000</b> 节点的子树<b>各 20 次</b>，记录事务总耗时 <b>P50/P99</b>、
 * 锁等待、{@code tree_move_subtree_size} 分布；再做 <b>10 并发交叉移动</b>测死锁率」。
 *
 * <h2>为什么重点是「耗时即锁持有时间」</h2>
 * <p>R2「验证什么」栏：那条前缀 LIKE UPDATE <b>跑在一个已持有 {@code FOR UPDATE} 锁的
 * 事务里</b> —— 耗时直接决定并发移动的吞吐与死锁概率。所以本类量的是
 * <b>整个移动事务</b>的墙钟耗时，不是那一条 UPDATE 单独的耗时。
 *
 * <h2>判据（R2「撞车后的影响面」栏）</h2>
 * <ul>
 *   <li><b>5000 节点子树的 UPDATE 超过 500 ms</b> → 02-数据库设计 §3.1.3 要点 3 已给方案
 *       （分批 UPDATE 每批 5000 行，同一事务内循环），<b>回头改模块 06</b>；
 *   <li><b>10 并发下出现死锁</b> → 说明 id 升序加锁没有覆盖全部加锁点，
 *       <b>属于铁律 1 未落地，不可上线</b>。
 * </ul>
 * <p><b>本类只测量与报告，不改设计</b>——两条处置都不在模块 07 的工单里。
 *
 * <h2>外加一条：嵌套子树并发移动</h2>
 * <p>模块 06 在 R2 表格之后追加登记的那一条：{@code NodeMoveConcurrencyIT} 的
 * 10 条移动<b>子树互不嵌套</b>，覆盖不到「步骤 5 的范围 UPDATE 按 {@code idx_ancestors}
 * 加锁、顺序由索引决定而非 id」这个残留风险。{@link #nestedSubtreeConcurrentMoves}
 * 专门把这一形态单独跑一遍。
 *
 * <h2>默认不跑</h2>
 * <p>建 1.1 万节点 + 60 次移动 + 两轮并发，单次运行分钟级，放进 {@code mvn verify}
 * 会把每次提交的反馈时间拖垮。用环境变量开：
 * <pre>EDUMATRIX_R2=1 mvn test -Dtest=R2SubtreeMovePerfIT</pre>
 */
@IntegrationTest
@EnabledIfEnvironmentVariable(named = "EDUMATRIX_R2", matches = "1",
        disabledReason = "R2 压测按需运行：EDUMATRIX_R2=1 mvn test -Dtest=R2SubtreeMovePerfIT")
class R2SubtreeMovePerfIT {

    /** R2 原文：单租户约 1.1 万节点。 */
    private static final int TARGET_NODES = 11_000;
    /** R2 原文：深度 6~8 层。 */
    private static final int[] SUBTREE_SIZES = {100, 1000, 5000};
    /** R2 原文：各 20 次。 */
    private static final int ROUNDS = 20;
    /** R2 原文：10 并发交叉移动。 */
    private static final int CONCURRENCY = 10;

    private static final long TENANT_ID = 1969000000000000001L;
    private static final long ROOT = TENANT_ID;
    private static final long OPERATOR_USER = 1969000000000900001L;

    /** 两个「停车位」：把子树在它们之间来回搬，每次都是真移动。 */
    private static final long PARK_A = 1969000000000000002L;
    private static final long PARK_B = 1969000000000000003L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NodeMoveService nodeMoveService;
    @Autowired
    private TestCurrentContextProvider context;

    private final List<Long> allNodes = new ArrayList<>();

    @BeforeEach
    void seed() {
        clean();
        TenantHelper.setProvider(context);
        context.asTenantUser(TENANT_ID, OPERATOR_USER, ROOT);
        buildTree();
    }

    @AfterEach
    void tearDown() {
        clean();
        context.asNoSession();
        TenantHelper.reset();
    }

    // =====================================================================
    // 判据一：100 / 1000 / 5000 节点子树各移动 20 次的 P50 / P99
    // =====================================================================

    @Test
    @DisplayName("R2：100 / 1000 / 5000 节点子树各移动 20 次，记 P50 / P99 与锁等待")
    void subtreeMoveLatency() {
        System.out.printf("%n========== R2 子树移动耗时（树规模 %d 节点）==========%n",
                countNodes());

        for (int size : SUBTREE_SIZES) {
            long subtreeRoot = findSubtreeRootOfAtLeast(size);
            int actual = countSubtree(subtreeRoot);

            List<Long> micros = new ArrayList<>(ROUNDS);
            long lockWaitBefore = innodbRowLockWaits();

            for (int i = 0; i < ROUNDS; i++) {
                long target = (i % 2 == 0) ? PARK_B : PARK_A;
                long t0 = System.nanoTime();
                nodeMoveService.move(subtreeRoot, target, new NodeMoveOptions("R2 压测", false));
                micros.add((System.nanoTime() - t0) / 1000L);
            }
            long lockWaitAfter = innodbRowLockWaits();

            Collections.sort(micros);
            System.out.printf(
                    "子树 %5d 节点（请求 %d）｜%d 次｜P50 %7.1f ms｜P99 %7.1f ms｜"
                            + "max %7.1f ms｜锁等待增量 %d 次%n",
                    actual, size, ROUNDS,
                    percentile(micros, 50) / 1000.0,
                    percentile(micros, 99) / 1000.0,
                    micros.get(micros.size() - 1) / 1000.0,
                    lockWaitAfter - lockWaitBefore);

            // R2 判据：5000 节点子树超过 500ms 就要回头改模块 06（分批 UPDATE）
            if (actual >= 5000 && percentile(micros, 99) / 1000.0 > 500) {
                System.out.printf("  ⚠ P99 超过 500 ms —— 触发 R2「撞车后的影响面」第一条："
                        + "02-数据库设计 §3.1.3 要点 3 的分批 UPDATE 方案，回头改模块 06%n");
            }
        }
        System.out.printf("tree_move_subtree_size 分布即上表的「子树 N 节点」列"
                + "（契约 §7.1 的 Histogram 由 NodeMoveService 在 afterCommit 记录）%n");
    }

    // =====================================================================
    // 判据二：10 并发交叉移动的死锁率
    // =====================================================================

    @Test
    @DisplayName("R2：10 并发交叉移动，死锁率必须为 0（否则铁律 1 未落地，不可上线）")
    void concurrentCrossMovesDeadlockRate() throws Exception {
        List<Long> movers = pickDistinctSubtreeRoots(CONCURRENCY);
        System.out.printf("%n========== R2 并发交叉移动（%d 并发，子树互不嵌套）==========%n",
                CONCURRENCY);
        runConcurrent(movers, "互不嵌套");
    }

    /**
     * <b>模块 06 追加登记的那一条形态</b>：被移动的子树<b>互相嵌套</b>。
     *
     * <p>{@code NodeMoveConcurrencyIT} 的 10 条移动子树互不嵌套，覆盖不到
     * 「步骤 5 的范围 UPDATE 按 {@code idx_ancestors} 加锁、顺序由索引决定而非 id」——
     * A 的范围更新中途撞上 B 锁住的行，是那条残留风险的确切形态。
     */
    @Test
    @DisplayName("R2 追加：嵌套子树并发移动（步骤 5 范围 UPDATE 的残留风险，模块 06 登记）")
    void nestedSubtreeConcurrentMoves() throws Exception {
        List<Long> nested = pickNestedChain(CONCURRENCY);
        System.out.printf("%n========== R2 并发交叉移动（%d 并发，子树【互相嵌套】）==========%n",
                nested.size());
        System.out.printf("嵌套链：%s%n", nested);
        runConcurrent(nested, "互相嵌套");
    }

    private void runConcurrent(List<Long> movers, String shape) throws Exception {
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(movers.size());
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < movers.size(); i++) {
            final long nodeId = movers.get(i);
            final long target = (i % 2 == 0) ? PARK_A : PARK_B;
            tasks.add(() -> {
                // 每个线程各自的会话：TenantHelper 的 provider 是静态的，
                // 但 TestCurrentContextProvider 存的是 ThreadLocal 之外的字段 ——
                // 这里所有线程同租户同操作人，共享一份即可
                try {
                    nodeMoveService.move(nodeId, target, new NodeMoveOptions("R2 并发", false));
                    ok.incrementAndGet();
                } catch (RuntimeException e) {
                    if (isDeadlock(e)) {
                        deadlocks.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                }
                return null;
            });
        }
        List<Future<Void>> futures = pool.invokeAll(tasks, 3, TimeUnit.MINUTES);
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();

        System.out.printf("形态=%s｜成功 %d｜死锁 %d｜其它拒绝 %d｜死锁率 %.0f%%%n",
                shape, ok.get(), deadlocks.get(), other.get(),
                100.0 * deadlocks.get() / movers.size());
        if (deadlocks.get() > 0) {
            System.out.printf("  ⚠ 触发 R2「撞车后的影响面」第二条：id 升序加锁未覆盖全部加锁点"
                    + " —— 属于铁律 1 未落地，【不可上线】。本模块不自行改设计，上报需方。%n");
        }
    }

    // =====================================================================
    // 造树：1.1 万节点、深度 6~8 层
    // =====================================================================

    private void buildTree() {
        jdbc.update("INSERT INTO sys_tenant (id, root_node_id, name, expire_time, status, "
                + "max_student_count, create_time, update_time, deleted_at) "
                + "VALUES (?, ?, 'R2 压测机构', NULL, 0, 99999, NOW(), NOW(), 0)", TENANT_ID, ROOT);

        insertNode(ROOT, 0L, "0", 1);
        insertNode(PARK_A, ROOT, "0," + ROOT, 1);
        insertNode(PARK_B, ROOT, "0," + ROOT, 1);

        // 【操作人必须在 sys_user 里真有一行】CurrentNodeResolver 走的是
        // SELECT node_id FROM sys_user WHERE id = ? —— 只在 TestCurrentContextProvider 里
        // 设个 userId 是不够的，那条查询会返回 0 行，move 的每次调用都在校验 2 之前就抛。
        // 第一版正是这么写的：11000 节点的树建得好好的，20 次移动【全部被拒】，
        // 而计数器只记「其它拒绝」，看上去像是并发问题
        jdbc.update("INSERT INTO sys_user (id, username, password, user_type, real_name, "
                + "phone, node_id, status, pwd_reset_flag, tenant_id, create_time, update_time, "
                + "deleted_at) VALUES (?, 'r2_operator', '$2a$10$r2', 1, 'R2 操作人', NULL, "
                + "?, 0, 0, ?, NOW(), NOW(), 0)", OPERATOR_USER, ROOT, TENANT_ID);
        allNodes.add(ROOT);
        allNodes.add(PARK_A);
        allNodes.add(PARK_B);

        // 分支因子 5：5^6 ≈ 15625，取到 1.1 万即停 —— 深度落在 6~8 层（含 ROOT）
        List<long[]> frontier = new ArrayList<>();
        frontier.add(new long[]{PARK_A, 2});
        long nextId = 1969000000000010000L;
        List<Object[]> batch = new ArrayList<>();

        while (allNodes.size() < TARGET_NODES && !frontier.isEmpty()) {
            List<long[]> next = new ArrayList<>();
            for (long[] parent : frontier) {
                if (allNodes.size() >= TARGET_NODES) {
                    break;
                }
                String parentAnc = ancestorsOf(parent[0]);
                for (int i = 0; i < 5 && allNodes.size() < TARGET_NODES; i++) {
                    long id = nextId++;
                    // 深度 ≤ 8 层后不再生子，控制在 R2 要求的 6~8 层
                    int depth = (int) parent[1] + 1;
                    batch.add(new Object[]{id, parent[0], parentAnc, TENANT_ID});
                    allNodes.add(id);
                    ancestorsCache.put(id, parentAnc + "," + parent[0]);
                    if (depth < 8) {
                        next.add(new long[]{id, depth});
                    }
                }
            }
            if (batch.size() > 2000) {
                flush(batch);
            }
            frontier = next;
        }
        flush(batch);
        // child_count 不参与本压测的判据，压测树不维护它（真实路径由移动事务维护）
        System.out.printf("R2 压测树已就绪：%d 个节点%n", countNodes());
    }

    private final java.util.Map<Long, String> ancestorsCache = new java.util.HashMap<>();

    private String ancestorsOf(long nodeId) {
        if (nodeId == PARK_A || nodeId == PARK_B) {
            return "0," + ROOT;
        }
        if (nodeId == ROOT) {
            return "0";
        }
        return ancestorsCache.get(nodeId);
    }

    private void flush(List<Object[]> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                + "create_time, update_time, deleted_at) "
                + "VALUES (?, ?, CONCAT(?, ',', ?), CONCAT('N', ?), 1, ?, 0, 0, 0, 0, ?, "
                + "NOW(), NOW(), 0)",
                batch.stream().map(a -> new Object[]{
                        a[0], a[1], a[2], a[1], a[0], a[0], a[3]}).toList());
        batch.clear();
    }

    private void insertNode(long id, Long parentId, String ancestors, int nodeType) {
        jdbc.update("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                        + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, NOW(), NOW(), 0)",
                id, parentId, ancestors, "N" + id, nodeType, id, TENANT_ID);
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /**
     * 找一棵后代数 ≥ {@code size} 的子树根，取<b>最接近</b>的那个。
     *
     * <h2>不要用相关子查询在全表上排序</h2>
     * <p>第一版写的是「对每个节点算一次子树大小，再 ORDER BY 它取最小」——
     * 那是 11000 行 × 两遍前缀扫描的 <b>O(n²)</b>，实测跑不出来（用例卡死在这一句）。
     * 现在改为：<b>按深度取有限候选</b>（每层最多 8 个），逐个用 {@code idx_ancestors}
     * 数一次子树大小，在候选里挑最接近的。候选数是常数，总代价与树规模无关。
     */
    private long findSubtreeRootOfAtLeast(int size) {
        long best = 0;
        int bestSize = Integer.MAX_VALUE;
        // 深度越浅子树越大；2~7 层足以覆盖 100 / 1000 / 5000 三档
        for (int depth = 2; depth <= 7; depth++) {
            List<Long> candidates = jdbc.queryForList(
                    "SELECT id FROM org_node WHERE tenant_id = ? AND deleted_at = 0 "
                            + "AND id NOT IN (?, ?, ?) "
                            + "AND CHAR_LENGTH(ancestors) - CHAR_LENGTH(REPLACE(ancestors, ',', '')) = ? "
                            + "ORDER BY id LIMIT 8",
                    Long.class, TENANT_ID, ROOT, PARK_A, PARK_B, depth);
            for (Long candidate : candidates) {
                int n = countSubtree(candidate);
                if (n >= size && n < bestSize) {
                    best = candidate;
                    bestSize = n;
                }
            }
        }
        if (best == 0) {
            throw new IllegalStateException("压测树里找不到 ≥ " + size + " 节点的子树");
        }
        return best;
    }

    private int countSubtree(long nodeId) {
        String selfPrefix = jdbc.queryForObject(
                "SELECT CONCAT(ancestors, ',', id) FROM org_node WHERE id = ?", String.class, nodeId);
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM org_node WHERE tenant_id = ? AND deleted_at = 0 "
                        + "AND (id = ? OR ancestors = ? OR ancestors LIKE CONCAT(?, ',%'))",
                Integer.class, TENANT_ID, nodeId, selfPrefix, selfPrefix);
        return n == null ? 0 : n;
    }

    /** 取 n 棵<b>互不嵌套</b>的子树根：同一层的兄弟节点天然互不嵌套。 */
    private List<Long> pickDistinctSubtreeRoots(int n) {
        return jdbc.queryForList(
                "SELECT id FROM org_node WHERE tenant_id = ? AND deleted_at = 0 "
                        + "AND parent_id = ? ORDER BY id LIMIT ?",
                Long.class, TENANT_ID, firstChildOf(PARK_A), n);
    }

    /** 取一条<b>互相嵌套</b>的祖先链：每个都是下一个的祖先。 */
    private List<Long> pickNestedChain(int n) {
        List<Long> chain = new ArrayList<>();
        long cur = firstChildOf(PARK_A);
        while (chain.size() < n) {
            chain.add(cur);
            Long child = jdbc.query(
                    "SELECT id FROM org_node WHERE tenant_id = ? AND deleted_at = 0 "
                            + "AND parent_id = ? ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null, TENANT_ID, cur);
            if (child == null) {
                break;
            }
            cur = child;
        }
        return chain;
    }

    private long firstChildOf(long parentId) {
        Long id = jdbc.query("SELECT id FROM org_node WHERE tenant_id = ? AND deleted_at = 0 "
                        + "AND parent_id = ? ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, TENANT_ID, parentId);
        if (id == null) {
            throw new IllegalStateException("压测树未建好");
        }
        return id;
    }

    private int countNodes() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM org_node WHERE tenant_id = ? AND deleted_at = 0",
                Integer.class, TENANT_ID);
        return n == null ? 0 : n;
    }

    /** InnoDB 累计行锁等待次数，差值即本轮的锁等待。 */
    private long innodbRowLockWaits() {
        Long v = jdbc.query("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'",
                rs -> rs.next() ? rs.getLong(2) : 0L);
        return v == null ? 0L : v;
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("Deadlock found") || m.contains("Lock wait timeout"))) {
                return true;
            }
        }
        return false;
    }

    private void clean() {
        jdbc.update("DELETE FROM sys_user WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM org_node_change_log WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM org_node WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM sys_tenant WHERE id = ?", TENANT_ID);
        allNodes.clear();
        ancestorsCache.clear();
    }
}
