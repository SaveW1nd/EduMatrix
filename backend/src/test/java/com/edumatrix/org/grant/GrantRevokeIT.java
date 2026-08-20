package com.edumatrix.org.grant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块 11 · C5：接口 39 撤销资源授权（<b>级联子树</b>）（03-02 §9.3、契约 §2.5 规则 5）。
 *
 * <p>本类的头号用例是 <b>PRD FR-4 的验收标准原文</b>：
 * 「甲把课程 K 授给下级管理员乙、乙授给教师丙、丙授给 8 名学员，甲撤销乙对 K 的授权，
 * Then 乙、丙及 8 名学员对 K 的授权行<b>全部被撤销（共 10 行）且在同一事务内完成</b>；
 * 8 名学员的 {@code vod_watch_progress} <b>一行不删</b>。」
 */
class GrantRevokeIT extends GrantIntegrationTestBase {

    private static final String GRANTS = "/api/v1/org/grants";

    // =====================================================================
    // FR-4 验收标准
    // =====================================================================

    @Test
    @DisplayName("⚠ FR-4：甲撤销乙 → 乙/丙/8 名学员共 10 行全部撤销，学习记录一行不删")
    void cascadeAcrossFourLevels() throws Exception {
        seedFourLevelChain();
        seedWatchProgressForStudents();
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1)).isEqualTo(10);

        JsonNode resp = deleteWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"],
                 "reason":"该课程版权到期，全线收回"}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("revokedCount").asInt())
                .as("乙 1 + 丙 1 + 8 名学员 = 10 行")
                .isEqualTo(10);
        assertThat(data(resp).path("directRevokedCount").asInt()).isEqualTo(1);
        assertThat(data(resp).path("cascadeRevokedCount").asInt()).isEqualTo(9);
        assertThat(data(resp).path("affectedNodeCount").asInt()).isEqualTo(10);
        assertThat(data(resp).path("affectedStudentCount").asInt())
                .as("PRD FR-4 规则 6 要的「其中学员 M 名」")
                .isEqualTo(8);
        assertThat(data(resp).path("learningRecordsRetained").asBoolean()).isTrue();

        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1))
                .as("库里一条有效授权都不剩 —— 不留「父级已无权、子级仍持有」的悬挂行")
                .isZero();
        assertThat(watchProgressCount())
                .as("撤销是【权限动作，不是数据销毁动作】：学习记录一行不删（契约 §2.5 规则 5）")
                .isEqualTo(GrantFixtures.S.length);
    }

    @Test
    @DisplayName("FR-4：撤销在【同一事务】内完成 —— 中途失败一行都不落")
    void allOrNothing() throws Exception {
        seedFourLevelChain();

        // 两个目标：A1 合法、第二个不存在 → 整批拒绝
        JsonNode resp = deleteWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d","1971000000000009997"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)));

        assertThat(code(resp)).isEqualTo(ErrorCode.NODE_NOT_FOUND.getCode());
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1))
                .as("合法的那个目标也不能被撤 —— 要么全撤要么不撤")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("cascadeDetail 完整披露影响面（样本 + 完整数量），否则等于静默替人做决定")
    void cascadeDetailDisclosesImpact() throws Exception {
        seedFourLevelChain();

        JsonNode detail = data(deleteWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1))))
                .path("cascadeDetail").get(0);

        assertThat(detail.path("resourceName").asText()).isEqualTo("高三数学·函数与导数");
        assertThat(detail.path("targetNodeName").asText()).isEqualTo("华东大区");
        assertThat(detail.path("cascadeNodeCount").asInt())
                .as("丙 + 8 名学员 = 9，【不含】目标节点自身")
                .isEqualTo(9);
        assertThat(detail.path("cascadeNodes").size()).isEqualTo(9);
    }

    // =====================================================================
    // 级联是强制的
    // =====================================================================

    @Test
    @DisplayName("⚠ 撤中间层：丙与其 8 名学员一并撤，乙【不动】—— 级联向下不向上")
    void cascadeGoesDownNotUp() throws Exception {
        seedFourLevelChain();

        JsonNode resp = deleteWithToken(GRANTS, loginAs(GrantFixtures.A1), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.T1)));

        assertThat(data(resp).path("revokedCount").asInt()).isEqualTo(9);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.A1))
                .as("撤下级不该影响上级自己持有的那一行")
                .isEqualTo(1);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.S[0])).isZero();
    }

    @Test
    @DisplayName("请求体里没有 cascade 开关 —— 传了也不会被识别（级联无法关闭）")
    void thereIsNoCascadeSwitch() throws Exception {
        seedFourLevelChain();

        JsonNode resp = deleteWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"],
                 "cascade":false,"cascadeEnabled":false,"revokeCascade":false}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("revokedCount").asInt())
                .as("契约 §2.5 规则 5 / §9.3：级联是强制行为，不提供关闭开关。"
                        + "无论请求体里塞什么，10 行照撤")
                .isEqualTo(10);
    }

    // =====================================================================
    // 幂等与越权
    // =====================================================================

    @Test
    @DisplayName("幂等：对不存在或已撤销的组合重复调用 → 200 且 revokedCount = 0")
    void idempotent() throws Exception {
        seedFourLevelChain();
        String token = loginAs(GrantFixtures.ROOT);
        String payload = body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1));

        assertThat(data(deleteWithToken(GRANTS, token, payload)).path("revokedCount").asInt())
                .isEqualTo(10);
        JsonNode again = deleteWithToken(GRANTS, token, payload);
        assertThat(code(again)).isEqualTo(200);
        assertThat(data(again).path("revokedCount").asInt()).isZero();
        assertThat(data(again).path("cascadeDetail").size())
                .as("什么都没撤时不该编出一条披露行")
                .isZero();
    }

    @Test
    @DisplayName("10302：撤销子树外的节点被拒；跨租户按 10101 处理")
    void outOfSubtreeIsRejected() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T2, GrantFixtures.ROOT);

        assertThat(code(deleteWithToken(GRANTS, loginAs(GrantFixtures.T1), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.T2)))))
                .isEqualTo(ErrorCode.GRANT_TARGET_OUT_OF_SUBTREE.getCode());
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.T2))
                .isEqualTo(1);

        assertThat(code(deleteWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.ROOT2)))))
                .as("跨租户被插件过滤掉 → 与「不存在」同一个出口，不暴露存在性")
                .isEqualTo(ErrorCode.NODE_NOT_FOUND.getCode());
    }

    // =====================================================================
    // 撤销后的连锁语义
    // =====================================================================

    @Test
    @DisplayName("撤销后被撤方立刻失去【再下发】能力（下一次授权就报 10301）")
    void revokedHolderCannotRegrant() throws Exception {
        seedFourLevelChain();
        deleteWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)));

        assertThat(code(postWithToken(GRANTS, loginAs(GrantFixtures.A1), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.T1)))))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND_OR_NO_GRANT_RIGHT.getCode());
    }

    @Test
    @DisplayName("撤销行保留为审计链：deleted_at 是【毫秒时间戳】不是 1，且 remark 记了原因")
    void revokedRowKeepsAuditTrail() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        deleteWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"],"reason":"版权到期"}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)));

        Long deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM org_resource_grant WHERE resource_id = ? "
                        + "AND target_node_id = ?", Long.class,
                GrantFixtures.C1, GrantFixtures.A1);
        assertThat(deletedAt)
                .as("唯一键 uk_resource_target 含 deleted_at：若每次都写同一个 1，"
                        + "「授→撤→再授→再撤」到第二次撤销就撞唯一键（契约 §2.2）")
                .isGreaterThan(1_600_000_000_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remark FROM org_resource_grant WHERE resource_id = ? AND target_node_id = ?",
                String.class, GrantFixtures.C1, GrantFixtures.A1))
                .isEqualTo("版权到期");
    }

    @Test
    @DisplayName("授 → 撤 → 再授 → 再撤：唯一键含 deleted_at 时不撞键")
    void grantRevokeGrantRevokeDoesNotHitUniqueKey() throws Exception {
        String token = loginAs(GrantFixtures.ROOT);
        String grantBody = body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1));

        for (int round = 1; round <= 2; round++) {
            assertThat(code(postWithToken(GRANTS, token, grantBody)))
                    .as("第 %d 轮授权", round).isEqualTo(200);
            assertThat(code(deleteWithToken(GRANTS, token, grantBody)))
                    .as("第 %d 轮撤销 —— 写定值 1 的实现会在这里撞 Duplicate entry", round)
                    .isEqualTo(200);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_resource_grant WHERE resource_id = ? AND target_node_id = ?",
                Integer.class, GrantFixtures.C1, GrantFixtures.A1))
                .as("两条已撤销的历史行都在，审计链完整")
                .isEqualTo(2);
    }

    // ================================================================ 辅助

    /** 甲(ROOT) → 乙(A1) → 丙(T1) → 8 名学员，共 10 行。 */
    private void seedFourLevelChain() {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        for (long student : GrantFixtures.S) {
            grantFixtures.grant(1, GrantFixtures.C1, student, GrantFixtures.T1);
        }
    }

    /** 给 8 名学员各造一条学习进度 —— 撤销后必须一行不删。 */
    private void seedWatchProgressForStudents() {
        for (int i = 0; i < GrantFixtures.S.length; i++) {
            // student_id 指向 org_student.id（不是节点 ID）—— 夹具里档案行 id = nodeId + 500。
            // vod_watch_progress 没有 video_id 列（视频由 lesson 承载），别照着别的表想当然
            jdbcTemplate.update("INSERT INTO vod_watch_progress (id, student_id, lesson_id, "
                            + "course_id, watched_duration, max_position, watch_status, "
                            + "tenant_id, create_time, update_time, deleted_at) "
                            + "VALUES (?, ?, ?, ?, 120, 120, 1, ?, NOW(), NOW(), 0)",
                    1971000000000800001L + i, GrantFixtures.S[i] + 500,
                    1971000000000700001L + i, GrantFixtures.C1,
                    GrantFixtures.TENANT_ID);
        }
    }

    private int watchProgressCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vod_watch_progress WHERE tenant_id = ?",
                Integer.class, GrantFixtures.TENANT_ID);
        return n == null ? 0 : n;
    }

    private static String body(String json) {
        return json.replace("\n", " ");
    }
}
