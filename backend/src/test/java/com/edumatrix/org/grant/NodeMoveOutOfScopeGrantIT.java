package com.edumatrix.org.grant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
import com.edumatrix.org.node.service.NodeMoveOperLogWriter;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块 11 · C8：接管模块 06 留下的 {@code revokeOutOfScopeGrants} 空转开关。
 *
 * <p>04 §B 模块 11「做完什么算做完」的<b>最后一条</b>，逐字两条 Given-When-Then：
 * <ul>
 *   <li>传 {@code true} → 该子树内<b>由原上级授予、现已跨出其管辖范围</b>的授权
 *       <b>全部被撤销</b>，响应的 {@code outOfScopeGrants} 是<b>已回收</b>的清单；
 *   <li>传 {@code false}（或不传）→ 授权<b>一律保留</b>，清单是<b>仍保留</b>的那一份。
 * </ul>
 *
 * <p><b>判据是「授权确实被撤了」，不是「调用了那个方法」</b> ——
 * 在此之前它是个只有开关没有动作的半成品，而那个半成品同样返回 200。
 */
class NodeMoveOutOfScopeGrantIT extends GrantIntegrationTestBase {

    private static String movePath(long nodeId) {
        return "/api/v1/org/nodes/" + nodeId + "/move";
    }

    @Test
    @DisplayName("⚠ revokeOutOfScopeGrants=true：跨管辖授权【确实被撤】，且级联到学员")
    void trueActuallyRevokes() throws Exception {
        seedChainThenMoveTarget();

        JsonNode resp = putWithToken(movePath(GrantFixtures.T1), loginAs(GrantFixtures.ROOT), body("""
                {"toParentId":"%d","revokeOutOfScopeGrants":true,"reason":"教师调岗"}
                """.formatted(GrantFixtures.A2)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("outOfScopeGrants").size())
                .as("T1 那一行跨管辖：owner 是 ROOT，但 A1（链上唯一持有者）已不在 T1 的新祖先链上")
                .isGreaterThanOrEqualTo(1);

        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.T1))
                .as("传了 true 就必须真的撤 —— 在此之前这里恒为 1，而接口照样返回 200")
                .isZero();
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.S[0]))
                .as("⚠ 必须【级联】：学员的链经过 T1 仍然完整，所以学员不在清单里。"
                        + "只撤清单里的行会让学员当场变成悬挂授权 —— "
                        + "一次「清理」制造了它本来要清理的东西")
                .isZero();
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.A1))
                .as("A1 没被移动，它自己那一行不受影响")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("revokeOutOfScopeGrants=false：授权一律保留，清单是「仍保留」的那一份，"
            + "并写一行 sys_oper_log 记下是谁选的")
    void falseKeepsEverythingAndRecordsWhoChose() throws Exception {
        seedChainThenMoveTarget();
        int logsBefore = grantFixtures.operLogCount(
                NodeMoveOperLogWriter.ACTION_KEEP_OUT_OF_SCOPE_GRANTS);

        JsonNode resp = putWithToken(movePath(GrantFixtures.T1), loginAs(GrantFixtures.ROOT), body("""
                {"toParentId":"%d","revokeOutOfScopeGrants":false,"reason":"教师调岗"}
                """.formatted(GrantFixtures.A2)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("outOfScopeGrants").size()).isGreaterThanOrEqualTo(1);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.T1))
                .as("选了保留就不回收 —— 否则每次转移都会静默中断学员正在学的课程（契约 §2.5 规则 9）")
                .isEqualTo(1);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.S[0]))
                .isEqualTo(1);

        // 【F-114 定案三】false 的语义是「我知道会留下跨管辖，是有意的」——
        // 那就必须查得出「是谁、什么时候选的」
        assertThat(data(resp).path("revokedOutOfScopeGrants").asBoolean())
                .as("入参原样回传：光看 outOfScopeGrants 分不出「已全撤」与「留下的就是这些」")
                .isFalse();
        assertThat(data(resp).path("retentionRecorded").asBoolean()).isTrue();
        assertThat(grantFixtures.operLogCount(
                NodeMoveOperLogWriter.ACTION_KEEP_OUT_OF_SCOPE_GRANTS))
                .as("action 与切面写的「移动节点」是两个不同的值 —— "
                        + "合并之后就没办法只查「有人明确选了保留」的那些行")
                .isEqualTo(logsBefore + 1);
    }

    @Test
    @DisplayName("⚠ F-114 定案三：revokeOutOfScopeGrants 不传 → 400，且树一动不动")
    void omittingTheChoiceIsRejected() throws Exception {
        seedChainThenMoveTarget();
        Long parentBefore = grantFixtures.parentOf(GrantFixtures.T1);

        JsonNode resp = putWithToken(movePath(GrantFixtures.T1), loginAs(GrantFixtures.ROOT), body("""
                {"toParentId":"%d","reason":"教师调岗"}
                """.formatted(GrantFixtures.A2)));

        assertThat(code(resp))
                .as("原来它选填、默认 false。默认 false 的问题不是选错了一边，"
                        + "而是「留下跨管辖授权」可以在没有人做过任何决定的情况下发生")
                .isEqualTo(400);
        assertThat(grantFixtures.parentOf(GrantFixtures.T1))
                .as("校验失败不得留下任何痕迹")
                .isEqualTo(parentBefore);
        assertThat(grantFixtures.operLogCount(
                NodeMoveOperLogWriter.ACTION_KEEP_OUT_OF_SCOPE_GRANTS))
                .as("被拒的那次不是一次「决定」，不能记成有人选了保留")
                .isZero();
    }

    @Test
    @DisplayName("F-114 定案三：显式传 false 但一条跨管辖都没有 → 照样记一行（0 也要记）")
    void explicitFalseIsRecordedEvenWhenNothingWouldBeKept() throws Exception {
        // 链完整：移动后没有任何一行跨管辖
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A2, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);

        JsonNode resp = putWithToken(movePath(GrantFixtures.T1), loginAs(GrantFixtures.ROOT), body("""
                {"toParentId":"%d","revokeOutOfScopeGrants":false}
                """.formatted(GrantFixtures.A2)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("outOfScopeGrants").size()).isZero();
        assertThat(grantFixtures.operLogCount(
                NodeMoveOperLogWriter.ACTION_KEEP_OUT_OF_SCOPE_GRANTS))
                .as("「当时确实没有跨管辖授权」与「没人做过这个决定」是两件事，"
                        + "事后只有这行日志能把它们分开")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("resourceName 不再恒为 null —— 模块 06 给它留的那个洞由本模块补上")
    void resourceNameIsResolvedNow() throws Exception {
        seedChainThenMoveTarget();

        JsonNode row = data(putWithToken(movePath(GrantFixtures.T1), loginAs(GrantFixtures.ROOT),
                body("""
                        {"toParentId":"%d","revokeOutOfScopeGrants":false}
                        """.formatted(GrantFixtures.A2))))
                .path("outOfScopeGrants").get(0);

        assertThat(row.path("resourceName").asText())
                .as("资源名在 crs_course 里，模块 06 读不到所以恒为 null；"
                        + "本模块经 GrantableResourceProvider 取到")
                .isEqualTo("高三数学·函数与导数");
    }

    @Test
    @DisplayName("链完整时清单为空 —— 不把「管辖关系没变」的行塞进操作者的待办")
    void intactChainProducesEmptyList() throws Exception {
        // 把 C1 授给 A2（新上级）：移动之后 T1 的链经 A2 仍然完整
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A2, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);

        JsonNode resp = putWithToken(movePath(GrantFixtures.T1), loginAs(GrantFixtures.ROOT), body("""
                {"toParentId":"%d","revokeOutOfScopeGrants":true}
                """.formatted(GrantFixtures.A2)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("outOfScopeGrants").size()).isZero();
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.T1))
                .as("链完整就不该撤 —— 传 true 也不撤")
                .isEqualTo(1);
    }

    // ================================================================ 辅助

    /** ROOT→A1→T1→学员 的授权链；A2 分支【不持有】C1，于是移动后 T1 跨管辖。 */
    private void seedChainThenMoveTarget() {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        for (long student : GrantFixtures.S) {
            grantFixtures.grant(1, GrantFixtures.C1, student, GrantFixtures.T1);
        }
    }

    private static String body(String json) {
        return json.replace("\n", " ");
    }
}
