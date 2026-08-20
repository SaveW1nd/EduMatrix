package com.edumatrix.org.grant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.metrics.MetricsRegistry;
import com.edumatrix.org.grant.job.GrantConsistencyJob;
import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块 11 · C7：悬挂授权巡检（PRD FR-7）+ 接口 51（03-02 §9.6）+ 三个指标（契约 §7.1）。
 *
 * <h2>本类的头号断言：{@code dangling} 与 {@code crossScope} <b>永不相加</b></h2>
 * <p>契约 §2.5 规则 6：合并计数会让<b>任何一次教师调岗或学员转交</b>都使指标永久非 0，
 * 持续假警报，最终结果是<b>运维关掉告警、真悬挂也没人看</b>。
 * 本项目在 <b>F-20 已经为这条踩过一次</b>，所以这里造的是<b>两类同时存在</b>的场景 ——
 * 只造一类的话，把两个数加起来的实现照样全绿。
 */
class GrantHealthIT extends GrantIntegrationTestBase {

    private static final String HEALTH = "/api/v1/org/grants/health";

    @Autowired
    private GrantConsistencyJob grantConsistencyJob;

    @Autowired
    private MeterRegistry meterRegistry;

    // =====================================================================
    // 分类
    // =====================================================================

    @Test
    @DisplayName("⚠ 两类同时存在时，两个数【各是各的】—— 相加的实现在这里会露馅")
    void danglingAndCrossScopeAreCountedSeparately() throws Exception {
        seedOneDangling();
        seedOneCrossScope();

        String token = loginAs(GrantFixtures.ROOT);
        JsonNode resp = getWithToken(HEALTH + "?type=dangling", token);
        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("summary").path("danglingCount").asInt())
                .as("真悬挂：级联回收失效造成的（授权后没人动过树）")
                .isEqualTo(1);
        assertThat(data(resp).path("summary").path("crossScopeCount").asInt())
                .as("跨管辖：节点移动的合法产物。若实现把两者相加，这里会是 2 —— "
                        + "而那会让每次教师调岗都推高「一致性指标」")
                .isEqualTo(1);
        assertThat(data(resp).path("total").asInt())
                .as("type=dangling 的清单里只有真悬挂那一条")
                .isEqualTo(1);

        JsonNode cross = getWithToken(HEALTH + "?type=crossScope", token);
        assertThat(data(cross).path("total").asInt()).isEqualTo(1);
        assertThat(data(cross).path("list").get(0).path("targetNodeId").asText())
                .isEqualTo(String.valueOf(GrantFixtures.T2));
    }

    @Test
    @DisplayName("⚠ F-82：同一形状，靠异动轨迹分类 —— 有过移动的算 crossScope")
    void classificationUsesChangeLog() throws Exception {
        seedOneCrossScope();

        String token = loginAs(GrantFixtures.ROOT);
        assertThat(data(getWithToken(HEALTH + "?type=dangling", token))
                .path("summary").path("danglingCount").asInt())
                .as("两者【形态完全一样】（都是「父级无权、子级仍持有」），"
                        + "差别只在成因。没有异动轨迹这条判据就分不出来 —— "
                        + "而分不出来时把它算作真悬挂，指标就永久非 0")
                .isZero();
        assertThat(data(getWithToken(HEALTH + "?type=crossScope", token))
                .path("summary").path("crossScopeCount").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("契约 §2.5 规则 12：指向已删除资源的授权行【不计为悬挂】")
    void deletedResourceIsNotDangling() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        jdbcTemplate.update("UPDATE crs_course SET deleted_at = 1755000000000 WHERE id = ?",
                GrantFixtures.C1);

        assertThat(data(getWithToken(HEALTH + "?type=dangling", loginAs(GrantFixtures.ROOT)))
                .path("summary").path("danglingCount").asInt())
                .as("资源状态可逆（下架可再上架、软删可恢复），授权行原样保留、"
                        + "恢复后自动重新生效。计进悬挂会让指标被一批「本来就该保留」的行淹掉")
                .isZero();
    }

    @Test
    @DisplayName("正常的逐级授权链：一条都不报（否则运营每天看一屏假警报）")
    void healthyChainReportsNothing() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        for (long student : GrantFixtures.S) {
            grantFixtures.grant(1, GrantFixtures.C1, student, GrantFixtures.T1);
        }

        JsonNode resp = getWithToken(HEALTH + "?type=dangling", loginAs(GrantFixtures.ROOT));
        assertThat(data(resp).path("summary").path("danglingCount").asInt()).isZero();
        assertThat(data(resp).path("summary").path("crossScopeCount").asInt()).isZero();
    }

    // =====================================================================
    // 接口 51 的其余语义
    // =====================================================================

    @Test
    @DisplayName("missingNodeId = 该补授给谁；expiring 时为 null（§9.6 字段说明）")
    void missingNodeAndExpiring() throws Exception {
        seedOneDangling();
        grantFixtures.grant(1, GrantFixtures.C3, GrantFixtures.A1, GrantFixtures.ROOT,
                null, java.time.LocalDateTime.now().plusDays(10)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        String token = loginAs(GrantFixtures.ROOT);
        JsonNode dangling = data(getWithToken(HEALTH + "?type=dangling", token)).path("list").get(0);
        assertThat(dangling.path("missingNodeId").asText())
                .as("「补授上级」要补的就是它 —— 父节点 A1")
                .isEqualTo(String.valueOf(GrantFixtures.A1));
        assertThat(dangling.path("resourceName").asText()).isEqualTo("高三数学·函数与导数");

        JsonNode expiring = data(getWithToken(HEALTH + "?type=expiring", token));
        assertThat(expiring.path("total").asInt()).isEqualTo(1);
        assertThat(expiring.path("list").get(0).path("missingNodeId").isNull()).isTrue();
    }

    @Test
    @DisplayName("数据权限：只返回落在我子树内的行（不在子树内的【不返回】，不暴露存在性）")
    void subtreeFiltered() throws Exception {
        seedOneDangling();   // 目标是 T1，在 A1 子树内

        assertThat(data(getWithToken(HEALTH + "?type=dangling", loginAs(GrantFixtures.A2)))
                .path("total").asInt())
                .as("A2 分支看不到 A1 分支的巡检结果")
                .isZero();
        assertThat(data(getWithToken(HEALTH + "?type=dangling", loginAs(GrantFixtures.A1)))
                .path("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("type 非法 → 400；教师无 org:grantHealth:list → 403")
    void badTypeAndForbidden() throws Exception {
        assertThat(code(getWithToken(HEALTH + "?type=whatever", loginAs(GrantFixtures.ROOT))))
                .isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(code(getWithToken(HEALTH + "?type=dangling", loginAs(GrantFixtures.T1))))
                .as("§9.6 权限栏：仅 org_admin")
                .isEqualTo(403);
    }

    // =====================================================================
    // 巡检任务与指标
    // =====================================================================

    @Test
    @DisplayName("⚠ 巡检任务打两个【独立】Gauge，且带 tenant 标签")
    void jobRecordsTwoIndependentGauges() throws Exception {
        seedOneDangling();
        seedOneCrossScope();

        grantConsistencyJob.run();

        String tenant = String.valueOf(GrantFixtures.TENANT_ID);
        assertThat(gauge(MetricsRegistry.GRANT_DANGLING_COUNT, tenant))
                .as("契约 §7.1：目标值恒 0、【进告警】")
                .isEqualTo(1.0);
        assertThat(gauge(MetricsRegistry.GRANT_CROSS_SCOPE_COUNT, tenant))
                .as("契约 §7.1：单独打点、【不进告警】。与上一个是两个 Gauge，不是一个数拆两半")
                .isEqualTo(1.0);
        assertThat(meterRegistry.find(MetricsRegistry.GRANT_ROWS_PER_NODE)
                .tag(MetricsRegistry.TAG_TENANT, tenant).summary())
                .as("grant_rows_per_node：盯单节点持有量，不是表总量")
                .isNotNull();
    }

    @Test
    @DisplayName("巡检【不改任何授权行、不动任何学习记录】（PRD FR-7 规则 5）")
    void jobChangesNothing() throws Exception {
        seedOneDangling();
        int before = grantFixtures.activeGrantCount(1, GrantFixtures.C1);

        grantConsistencyJob.run();

        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1))
                .as("只告警不自动删除 —— 避免误伤在学学员")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("巡检跑完后 detectedTime 有值（F-83：不落快照，只存最近一轮完成时刻）")
    void detectedTimeComesFromLastRun() throws Exception {
        seedOneDangling();
        String token = loginAs(GrantFixtures.ROOT);

        assertThat(data(getWithToken(HEALTH + "?type=dangling", token))
                .path("list").get(0).path("detectedTime").isNull())
                .as("从未巡检过时为 null —— 而【清单本身照常是准的】，"
                        + "丢的是「上次什么时候跑的」，不是「有没有问题」")
                .isTrue();

        grantConsistencyJob.run();

        assertThat(data(getWithToken(HEALTH + "?type=dangling", token))
                .path("list").get(0).path("detectedTime").asText()).isNotBlank();
    }

    // ================================================================ 辅助

    /** 真悬挂：ROOT 授给 A1、A1 授给 T1，然后【只撤 A1 那一行】（模拟级联回收失效）。 */
    private void seedOneDangling() {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        jdbcTemplate.update("UPDATE org_resource_grant SET deleted_at = 1755000000000 "
                + "WHERE resource_id = ? AND target_node_id = ?", GrantFixtures.C1, GrantFixtures.A1);
    }

    /**
     * 跨管辖：C3 授给 <b>T2</b>（A2 分支，A2 不持有 C3），并留下一条「教师调岗」异动轨迹。
     *
     * <p><b>必须用与 {@link #seedOneDangling} 不同的节点</b>：两者都挂在 T1 上时，
     * 这条异动轨迹会把<b>真悬挂那条也判成跨管辖</b> ——
     * 判据是「该节点或其祖先在授权之后有过移动」，不区分是哪一次授权。
     * 我第一版就是这么搭的，表现是 {@code danglingCount} 恒为 0 而
     * {@code crossScopeCount} 恒为 2，<b>看起来像分类实现错了，实际是夹具错了</b>。
     */
    private void seedOneCrossScope() {
        grantFixtures.grant(1, GrantFixtures.C3, GrantFixtures.T2, GrantFixtures.ROOT);
        jdbcTemplate.update("INSERT INTO org_node_change_log (id, node_id, change_type, "
                        + "from_parent_id, to_parent_id, change_time, operator_id, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, 4, ?, ?, NOW(), ?, ?, NOW(), NOW(), 0)",
                1971000000000006001L, GrantFixtures.T2, GrantFixtures.A1, GrantFixtures.A2,
                GrantFixtures.userIdOf(GrantFixtures.ROOT), GrantFixtures.TENANT_ID);
    }

    private Double gauge(String name, String tenant) {
        return meterRegistry.find(name)
                .tag(MetricsRegistry.TAG_TENANT, tenant).gauge().value();
    }
}
