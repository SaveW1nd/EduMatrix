package com.edumatrix.org.grant;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.metrics.MetricsRegistry;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.org.grant.job.GrantConsistencyJob;
import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
import com.edumatrix.system.file.entity.SysFile;
import com.edumatrix.system.file.service.FileService;
import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 需方 2026-08-21 定案：<b>授权一律永久有效</b>，全库不再有任何一处判有效期。
 * 本类是这次改动<b>唯一的判据</b>。
 *
 * <h2>⚠ 这次的风险不是删多了，是<b>删漏了一处</b></h2>
 * <p>漏掉的那一处会成为全系统<b>唯一</b>还在判有效期的地方：它与其余各处结论不一致，
 * <b>而且不报错</b> —— 表现是「同一条授权，走 A 路径能用、走 B 路径查不到」，
 * 两条路都返回 200。改前散在 7 个文件 16 处，靠人眼数是数不准的。
 *
 * <h2>探针：一条<b>两端各越一次</b>的授权行</h2>
 * <p>{@code valid_start} 在未来（2099）、{@code valid_end} 在过去（2020）。
 * <b>一行顶两行</b>：改前的谓词有两个半边
 *（{@code NOW() >= valid_start} 与 {@code valid_end >= NOW()}），
 * <b>任一半边活下来这行都会被滤掉</b>。
 * 若只插「已过期」的行，那么「只保留了 {@code valid_start} 那半边」的变异<b>照样全绿</b>。
 * 这一行在业务上没有意义，它不是业务数据 —— 它是探针。
 *
 * <h2>覆盖清单（需方点名的读路径，一条不少）</h2>
 * <table border="1">
 *   <caption>16 处判定 → 用例</caption>
 *   <tr><th>改前判定所在</th><th>钉它的用例</th></tr>
 *   <tr><td>{@code ResourceGrantMapper.VALID_NOW} 的四个调用点</td>
 *       <td>{@link #readerSeesTheExpiredRow}（四个方法逐个调）</td></tr>
 *   <tr><td>{@code canUse} 端到端（讲义附件下载）</td>
 *       <td>{@link #expiredGrantStillOpensTheMaterial}</td></tr>
 *   <tr><td>{@code canRegrant} / 接口 37 / 接口 38 的截断</td>
 *       <td>{@link #expiredHoldingIsStillRegrantable}</td></tr>
 *   <tr><td>{@code GrantQueryService.active()} / {@code expired} / {@code includeExpired}</td>
 *       <td>{@link #interface41ReturnsTheExpiredRow}</td></tr>
 *   <tr><td>{@code GrantHealthMapper.selectSuspects} 外层<b>与</b>子查询、
 *           {@code selectRowsPerNode}</td>
 *       <td>{@link #expiredRowsStillDecideDangling}（<b>两个方向各一条断言</b>）</td></tr>
 *   <tr><td>{@code TransferPrecheckMapper} / {@code NodeGrantScopeMapper} /
 *           {@code OutOfScopeGrantMapper}</td>
 *       <td>{@link #expiredRowShowsInPrecheckStatAndOutOfScope}</td></tr>
 * </table>
 */
class GrantNoValidityIT extends GrantIntegrationTestBase {

    /** 探针的生效时间：<b>在未来</b>。 */
    private static final String NOT_YET = "2099-01-01 00:00:00";
    /** 探针的失效时间：<b>在过去</b>。 */
    private static final String LONG_GONE = "2020-12-31 23:59:59";

    private static final String GRANTABLE = "/api/v1/org/grants/grantable-resources";
    private static final String GRANTS = "/api/v1/org/grants";
    private static final String HEALTH = "/api/v1/org/grants/health";
    private static final String PRECHECK = "/api/v1/org/students/transfer-precheck";

    @Autowired
    private ResourceGrantReader grantReader;

    @Autowired
    private FileService fileService;

    @Autowired
    private GrantConsistencyJob grantConsistencyJob;

    @Autowired
    private MeterRegistry meterRegistry;

    // =====================================================================
    // ① 公共层的四条读 —— 改前它们共用 VALID_NOW 那一段文本
    // =====================================================================

    @Test
    @DisplayName("⚠ ResourceGrantReader 四条读全部照常返回探针行（改前它们共用同一段有效期谓词）")
    void readerSeesTheExpiredRow() throws Exception {
        probeGrant(GrantFixtures.A1, GrantFixtures.ROOT);
        probeGrant(GrantFixtures.T1, GrantFixtures.A1);

        runAsNode(GrantFixtures.T1, () -> {
            assertThat(grantReader.hasGrant(ResourceType.COURSE, GrantFixtures.C1, GrantFixtures.T1))
                    .as("countActiveGrant —— 全系统最高频的那条点查。它还判有效期的话，"
                            + "学员在「到期」那天会被静默拒之门外，而接口返回 200")
                    .isTrue();
            assertThat(grantReader.grantedResourceIds(ResourceType.COURSE, GrantFixtures.T1))
                    .as("selectActiveResourceIds —— 「我能用哪些课」")
                    .contains(GrantFixtures.C1);

            Map<Long, Set<Long>> holders = grantReader.grantHolders(ResourceType.COURSE,
                    List.of(GrantFixtures.C1), List.of(GrantFixtures.ROOT, GrantFixtures.A1));
            assertThat(holders.getOrDefault(GrantFixtures.C1, Set.of()))
                    .as("selectGrantHolders —— 契约 §2.5 规则 9 的链判定靠它。"
                            + "漏掉一个持有者，整条链就被判成断了")
                    .contains(GrantFixtures.A1);

            assertThat(grantReader.countActiveTargets(ResourceType.COURSE, List.of(GrantFixtures.C1)))
                    .as("countActiveTargets —— 03-03 §1.1 课程列表的 grantedNodeCount")
                    .containsEntry(GrantFixtures.C1, 2);
        });
    }

    // =====================================================================
    // ② canUse：端到端，一直走到文件
    // =====================================================================

    @Test
    @DisplayName("⚠ canUse：探针行照样让学生打开讲义附件（判有效期的话这里会 404）")
    void expiredGrantStillOpensTheMaterial() throws Exception {
        probeGrant(GrantFixtures.A1, GrantFixtures.ROOT);
        probeGrant(GrantFixtures.T1, GrantFixtures.A1);
        probeGrant(GrantFixtures.S[0], GrantFixtures.T1);

        runAsNode(GrantFixtures.S[0], () -> {
            SysFile file = fileService.resolveForDownload(GrantFixtures.ATTACH_FILE);
            assertThat(file.getFileName())
                    .as("失效手段只剩两个：显式撤销（接口 39，级联）与学籍状态。"
                            + "时间不再是其中之一")
                    .isEqualTo("讲义.pdf");
        });
    }

    // =====================================================================
    // ③ canRegrant + 接口 37 + 接口 38（含「永远不写这两列」）
    // =====================================================================

    @Test
    @DisplayName("⚠ canRegrant：拿探针行照样能再下发；且新行的 valid_start/valid_end 落库为 NULL")
    void expiredHoldingIsStillRegrantable() throws Exception {
        probeGrant(GrantFixtures.A1, GrantFixtures.ROOT);
        probeGrant(GrantFixtures.T1, GrantFixtures.A1);

        String token = loginAs(GrantFixtures.T1);
        assertThat(resourceIds(getWithToken(GRANTABLE + "?resourceType=1&source=2", token)))
                .as("接口 37 是接口 38 的合法资源全集 —— 这里滤掉、那里就 10301")
                .contains(String.valueOf(GrantFixtures.C1));

        JsonNode resp = postWithToken(GRANTS, token, body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.S[0])));
        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("grantedCount").asInt()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_resource_grant WHERE resource_id = ? "
                        + "AND target_node_id = ? AND deleted_at = 0 "
                        + "AND valid_start IS NULL AND valid_end IS NULL",
                Integer.class, GrantFixtures.C1, GrantFixtures.S[0]))
                .as("【两列保留但永远不写】—— 留列删码。写侧再往这两列里写值，"
                        + "读侧又不判，就会长出一个「库里有值、没人看」的字段，"
                        + "而下一个人会以为它是有效的")
                .isEqualTo(1);
    }

    // =====================================================================
    // ④ 接口 41：不再过滤、不再标记
    // =====================================================================

    @Test
    @DisplayName("⚠ 接口 41：探针行默认就返回；expired 恒 false、validEnd 恒 null、includeExpired 恒无差别")
    void interface41ReturnsTheExpiredRow() throws Exception {
        probeGrant(GrantFixtures.A1, GrantFixtures.ROOT);

        String token = loginAs(GrantFixtures.ROOT);
        String path = "/api/v1/org/nodes/" + GrantFixtures.A1 + "/granted-resources";

        JsonNode plain = data(getWithToken(path, token));
        assertThat(plain.path("total").asInt())
                .as("改前这里默认过滤掉「不在有效期内」的行 —— 那正是要删的那一处")
                .isEqualTo(1);

        JsonNode row = plain.path("list").get(0);
        assertThat(row.path("expired").asBoolean()).isFalse();
        assertThat(row.path("validEnd").isNull())
                .as("需方定案点名：接口 41 的 validEnd【恒为 null】，不是缺陷")
                .isTrue();
        assertThat(row.path("validStart").isNull()).isTrue();

        assertThat(data(getWithToken(path + "?includeExpired=true", token)).path("total").asInt())
                .as("includeExpired 保留但【恒无差别】：没有任何一行会「过期」。"
                        + "参数不删是因为删它是接口签名变更")
                .isEqualTo(1);
    }

    // =====================================================================
    // ⑤ 巡检：两个方向各一条 —— 只测一个方向的话，两处谓词里有一处漏删也全绿
    // =====================================================================

    @Test
    @DisplayName("⚠ 巡检：探针行既能【被报成悬挂】，也能【使下级不再悬挂】——两处谓词各钉一个方向")
    void expiredRowsStillDecideDangling() throws Exception {
        // 方向一：只有 T1 持有（探针行），A1 一行都没有 → T1 那行【必须】被报成悬挂。
        // 若 selectSuspects 的【外层】谓词还在，这一行会被整个滤掉 → 这里读到 0
        probeGrant(GrantFixtures.T1, GrantFixtures.A1);

        String token = loginAs(GrantFixtures.ROOT);
        assertThat(data(getWithToken(HEALTH + "?type=dangling", token))
                .path("summary").path("danglingCount").asInt())
                .as("外层谓词若还在：过期行进不了候选集，真悬挂被静默漏报 —— "
                        + "而 grant_dangling_count 的告警线是 > 0，于是【永远不告警】")
                .isEqualTo(1);

        // 方向二：给 A1 也补一条探针行 → 链完整，T1 那行【必须】不再是悬挂。
        // 若 NOT EXISTS 子查询里的谓词还在，A1 这行会被子查询忽略 → 仍然读到 1
        probeGrant(GrantFixtures.A1, GrantFixtures.ROOT);

        JsonNode after = getWithToken(HEALTH + "?type=dangling", token);
        assertThat(data(after).path("summary").path("danglingCount").asInt())
                .as("子查询谓词若还在：父级明明持有却被当成没有，每一条正常链都被报成悬挂 —— "
                        + "方向与上一条【正好相反】，所以两条都要写。"
                        + "只写一条时，另一处漏删照样全绿")
                .isZero();
        assertThat(data(after).path("summary").path("crossScopeCount").asInt()).isZero();

        // selectRowsPerNode：谓词还在的话它一行都数不到，Summary 压根不会被注册
        grantConsistencyJob.run();
        assertThat(meterRegistry.find(MetricsRegistry.GRANT_ROWS_PER_NODE)
                .tag(MetricsRegistry.TAG_TENANT, String.valueOf(GrantFixtures.TENANT_ID)).summary())
                .as("grant_rows_per_node 也曾判有效期 —— 漏删的话它会少数一批行，"
                        + "而「少数了」和「本来就少」在一个 Histogram 上看不出来")
                .isNotNull();
    }

    // =====================================================================
    // ⑥ 接口 52 / 节点详情统计 / 跨管辖判定
    // =====================================================================

    @Test
    @DisplayName("⚠ 接口 52、节点详情 grantedResourceStat、移动后的 outOfScopeGrants 都照常看见探针行")
    void expiredRowShowsInPrecheckStatAndOutOfScope() throws Exception {
        probeGrant(GrantFixtures.A1, GrantFixtures.ROOT);
        probeGrant(GrantFixtures.T1, GrantFixtures.A1);
        probeGrant(GrantFixtures.S[0], GrantFixtures.T1);

        String token = loginAs(GrantFixtures.ROOT);

        // 接口 52：把学员转给不持有 C1 的 T2 → 影响面里必须有这一条
        JsonNode precheck = postWithToken(PRECHECK, token, body("""
                {"studentIds":["%d"],"toNodeId":"%d"}
                """.formatted(GrantFixtures.S[0] + 500, GrantFixtures.T2)));
        assertThat(code(precheck)).isEqualTo(200);
        assertThat(precheck.path("data").path("outOfScopeGrants").size())
                .as("TransferPrecheckMapper 复用的就是那个常量 —— 漏删的话预检说「没有影响」，"
                        + "而转过去之后学员当场用不了")
                .isEqualTo(1);

        // 节点详情 §3.2：grantedResourceStat（模块 06 的 NodeGrantScopeMapper，它也判过有效期）
        JsonNode stat = data(getWithToken("/api/v1/org/nodes/" + GrantFixtures.T1, token))
                .path("grantedResourceStat");
        assertThat(stat.path("courseCount").asInt())
                .as("模块 06 那一处是【第 16 处】—— 它不在 org/grant 包里，"
                        + "按包搜索会正好漏掉它")
                .isEqualTo(1);

        // 移动 T1 到 A2 名下 → 跨管辖判定必须看见探针行
        JsonNode move = putWithToken("/api/v1/org/nodes/" + GrantFixtures.T1 + "/move", token, body("""
                {"toParentId":"%d","reason":"教师调岗"}
                """.formatted(GrantFixtures.A2)));
        assertThat(code(move)).isEqualTo(200);
        assertThat(data(move).path("outOfScopeGrants").size())
                .as("OutOfScopeGrantMapper 也曾判有效期 —— 漏删的话移动响应说「没有跨管辖授权」，"
                        + "而实际有一条，两边都返回 200")
                .isGreaterThanOrEqualTo(1);
    }

    // ================================================================ 辅助

    /** 插一条<b>两端各越一次</b>的探针授权行（C1）。 */
    private void probeGrant(long targetNodeId, long granterNodeId) {
        grantFixtures.grant(1, GrantFixtures.C1, targetNodeId, granterNodeId, NOT_YET, LONG_GONE);
    }

    /** 以指定节点的会话直接调 Service（{@code ResourceGrantReader} / {@code FileService} 没有对应入口）。 */
    private void runAsNode(long nodeId, ThrowingRunnable action) throws Exception {
        testContextProvider.asTenantUser(GrantFixtures.TENANT_ID,
                GrantFixtures.userIdOf(nodeId), nodeId);
        com.edumatrix.common.tenant.TenantHelper.setProvider(testContextProvider);
        try {
            action.run();
        } finally {
            com.edumatrix.common.tenant.TenantHelper.setProvider(saTokenContextProvider);
            testContextProvider.asNoSession();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String body(String json) {
        return json.replace("\n", " ");
    }
}
