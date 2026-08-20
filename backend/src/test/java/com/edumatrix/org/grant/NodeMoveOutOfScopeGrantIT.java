package com.edumatrix.org.grant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
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
    @DisplayName("revokeOutOfScopeGrants=false（默认）：授权一律保留，清单是「仍保留」的那一份")
    void falseKeepsEverything() throws Exception {
        seedChainThenMoveTarget();

        JsonNode resp = putWithToken(movePath(GrantFixtures.T1), loginAs(GrantFixtures.ROOT), body("""
                {"toParentId":"%d","reason":"教师调岗"}
                """.formatted(GrantFixtures.A2)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("outOfScopeGrants").size()).isGreaterThanOrEqualTo(1);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.T1))
                .as("默认不回收 —— 否则每次转移都会静默中断学员正在学的课程（契约 §2.5 规则 9）")
                .isEqualTo(1);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.S[0]))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("resourceName 不再恒为 null —— 模块 06 给它留的那个洞由本模块补上")
    void resourceNameIsResolvedNow() throws Exception {
        seedChainThenMoveTarget();

        JsonNode row = data(putWithToken(movePath(GrantFixtures.T1), loginAs(GrantFixtures.ROOT),
                body("""
                        {"toParentId":"%d"}
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
