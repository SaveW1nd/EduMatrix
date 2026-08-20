package com.edumatrix.org;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.support.OrgFixtures;
import com.edumatrix.org.support.OrgIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 03-02 §3.4 移动节点 —— 本模块的核心判据。
 *
 * <p>覆盖 PRD F1-2 验收标准第 1 条（12 个后代整棵重算）、边界 B4（多层嵌套逐层断言）、
 * 04-实施计划.md 模块 06 规则 9/10（异动类型推断、教师调岗只写 1 条轨迹）。
 */
class NodeMoveIT extends OrgIntegrationTestBase {

    private static final String LVL2 = "0," + OrgFixtures.ROOT;
    private static final String UNDER_A2 = LVL2 + "," + OrgFixtures.A2;

    @Test
    @DisplayName("PRD F1-2：P 的子树含 12 个后代，移动后 13 个节点的 ancestors 全部重算，"
            + "且 affectedNodeCount 恰好是 13")
    void movingSubtreeRecalculatesEveryDescendant() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode response = move(token, OrgFixtures.P, OrgFixtures.A2);

        assertThat(code(response)).isEqualTo(200);
        JsonNode data = data(response);

        // 【本模块的唯一外部可见证据】affectedNodeCount = 步骤 5 的返回行数（12 个后代）
        // + 1（步骤 4 单独更新的被移动节点自身）。少 1 就说明那条前缀 UPDATE 没命中整棵子树
        assertThat(data.path("affectedNodeCount").asInt()).isEqualTo(13);
        assertThat(data.path("newAncestors").asText()).isEqualTo(UNDER_A2);
        assertThat(data.path("fromParentId").asText()).isEqualTo(String.valueOf(OrgFixtures.A1));
        assertThat(data.path("toParentId").asText()).isEqualTo(String.valueOf(OrgFixtures.A2));

        // 边界 B4：不是移一个叶子，而是移一棵四层子树，【每一层】都要重算对
        String newP = UNDER_A2;                                       // 深度 3
        String newA3 = newP + "," + OrgFixtures.P;                    // 深度 4
        String newT1 = newA3 + "," + OrgFixtures.A3;                  // 深度 5
        String newS1 = newT1 + "," + OrgFixtures.T1;                  // 深度 6
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.P)).isEqualTo(newP);
        assertThat(orgFixtures.parentOf(OrgFixtures.P)).isEqualTo(OrgFixtures.A2);
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.A3)).isEqualTo(newA3);
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.T1)).isEqualTo(newT1);
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.T2)).isEqualTo(newT1);
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.S1)).isEqualTo(newS1);
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.S5))
                .isEqualTo(newA3 + "," + OrgFixtures.A3 + "," + OrgFixtures.T2);
        // 另一支（T3 挂在 P 下，比 A3 支浅一层）也要对
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.T3)).isEqualTo(newP + "," + OrgFixtures.P);
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.S8))
                .isEqualTo(newP + "," + OrgFixtures.P + "," + OrgFixtures.T3);

        // 【parent_id 一个都没动】——移动只改被移动节点自己的父，子树的父子关系原样跟随
        assertThat(orgFixtures.parentOf(OrgFixtures.A3)).isEqualTo(OrgFixtures.P);
        assertThat(orgFixtures.parentOf(OrgFixtures.S1)).isEqualTo(OrgFixtures.T1);

        // PRD F1-2：「以 Y 为根做子树查询能命中全部 13 个节点」
        JsonNode subtree = client.getWithToken(
                "/api/v1/org/nodes/tree?rootId=" + OrgFixtures.P + "&deep=true&nodeTypes=1,2,3", token);
        assertThat(flatten(data(subtree))).hasSize(12);   // 不含树根自身，12 + P = 13
    }

    @Test
    @DisplayName("§3.1.3 步骤 6：child_count 与两条祖先链的 student_count 按增量维护")
    void redundantCountersAreMaintainedAlongBothChains() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(code(move(token, OrgFixtures.P, OrgFixtures.A2))).isEqualTo(200);

        assertThat(orgFixtures.childCountOf(OrgFixtures.A1)).isZero();
        assertThat(orgFixtures.childCountOf(OrgFixtures.A2)).isEqualTo(2);

        // 旧链 [ROOT, A1] 各 -8；新链 [ROOT, A2] 各 +8 —— ROOT 是两条链的公共祖先，净变化 0
        assertThat(orgFixtures.studentCountOf(OrgFixtures.ROOT)).isEqualTo(8);
        assertThat(orgFixtures.studentCountOf(OrgFixtures.A1)).isZero();
        assertThat(orgFixtures.studentCountOf(OrgFixtures.A2)).isEqualTo(8);
        // 子树内部的计数不受影响（它们的相对结构没变）
        assertThat(orgFixtures.studentCountOf(OrgFixtures.P)).isEqualTo(8);
        assertThat(orgFixtures.studentCountOf(OrgFixtures.A3)).isEqualTo(5);
    }

    @Test
    @DisplayName("分配导师（学生→教师）：changeType=2，且 org_teacher.student_count 两侧都维护")
    void assigningStudentToTeacherMaintainsTeacherProfileCount() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode response = move(token, OrgFixtures.S8, OrgFixtures.TX);

        assertThat(code(response)).isEqualTo(200);
        assertThat(data(response).path("affectedNodeCount").asInt()).isEqualTo(1);
        assertThat(data(response).path("changeType").asInt()).isEqualTo(2);

        // org_node 侧：旧链 [ROOT,A1,P,T3] -1，新链 [ROOT,A2,TX] +1
        assertThat(orgFixtures.studentCountOf(OrgFixtures.T3)).isEqualTo(2);
        assertThat(orgFixtures.studentCountOf(OrgFixtures.P)).isEqualTo(7);
        assertThat(orgFixtures.studentCountOf(OrgFixtures.A1)).isEqualTo(7);
        assertThat(orgFixtures.studentCountOf(OrgFixtures.ROOT)).isEqualTo(8);
        assertThat(orgFixtures.studentCountOf(OrgFixtures.TX)).isEqualTo(1);
        assertThat(orgFixtures.studentCountOf(OrgFixtures.A2)).isEqualTo(1);

        // 【§3.1.3 模板之外的那一步】org_teacher.student_count 与 org_node 同源同步。
        // 不维护它，接口 4 被直接调用时这个冗余列就静默变陈旧
        assertThat(orgFixtures.teacherStudentCountOf(OrgFixtures.T3)).isEqualTo(2);
        assertThat(orgFixtures.teacherStudentCountOf(OrgFixtures.TX)).isEqualTo(1);
    }

    @Test
    @DisplayName("规则 10：教师调岗只写 1 条 change_type=4，随行学员不逐条写 change_type=2")
    void teacherReassignWritesExactlyOneChangeLog() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode response = move(token, OrgFixtures.T3, OrgFixtures.A2);

        assertThat(code(response)).isEqualTo(200);
        // T3 + 名下 3 名学员
        assertThat(data(response).path("affectedNodeCount").asInt()).isEqualTo(4);
        assertThat(data(response).path("changeType").asInt()).isEqualTo(4);

        assertThat(orgFixtures.changeLogCount(OrgFixtures.T3)).isEqualTo(1);
        assertThat(orgFixtures.latestChangeType(OrgFixtures.T3)).isEqualTo(4);
        // 随行学员一条都不写（PRD F1-4 规则 6、DDL 对 node_id 的列注释）
        assertThat(orgFixtures.changeLogCount(OrgFixtures.S6)).isZero();
        assertThat(orgFixtures.changeLogCount(OrgFixtures.S7)).isZero();
        assertThat(orgFixtures.changeLogCount(OrgFixtures.S8)).isZero();
    }

    @Test
    @DisplayName("§3.4 映射表：学生→管理员 = 3 转交管理员；管理员→管理员 = 8 节点移动")
    void changeTypeIsInferredFromNodeTypes() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(data(move(token, OrgFixtures.S8, OrgFixtures.A2)).path("changeType").asInt())
                .isEqualTo(3);
        assertThat(data(move(token, OrgFixtures.P, OrgFixtures.A2)).path("changeType").asInt())
                .isEqualTo(8);
    }

    @Test
    @DisplayName("异动轨迹落在同一事务内：reason 与新旧父节点齐全，changeTime 取自数据库时钟")
    void changeLogIsWrittenInsideTheSameTransaction() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode response = client.putWithToken(
                "/api/v1/org/nodes/" + OrgFixtures.P + "/move", token,
                moveBody(OrgFixtures.A2, "华东大区撤并，苏州中心划归华南"));

        assertThat(code(response)).isEqualTo(200);
        assertThat(data(response).path("changeTime").asText()).isNotBlank();
        assertThat(orgFixtures.changeLogCount(OrgFixtures.P)).isEqualTo(1);
    }

    @Test
    @DisplayName("契约 §2.5 规则 9：移动后授权链断掉的进 outOfScopeGrants，且不被自动撤销")
    void outOfScopeGrantsAreReportedButNotRevoked() throws Exception {
        // 【判据已由模块 11 补齐】原先这条用的是模块 06 只能算的那半个判据
        //（「授权人所在节点还在不在祖先链上」），资源 ID 是编的、根本不在 crs_course 里。
        // 契约 §2.5 规则 9 的完整判据要读 owner_node_id 与【有效授权链】，
        // 于是读不到资源的行一律判为「不可再下发」——对照组也会进清单。故改用真实课程。
        long courseIntact = 1962000000000008001L;   // ROOT 自有，逐级授到 T1：链完整
        long courseBroken = 1962000000000008002L;   // A1 自有：P 移走后 A1 不在链上，链断
        orgFixtures.course(courseIntact, "链完整的课程", OrgFixtures.ROOT);
        orgFixtures.course(courseBroken, "原上级自有的课程", OrgFixtures.A1);

        // 逐级显式授权（契约 §2.5 规则 3：每一层都要有），含【移动后的新父】A2
        long seq = 1962000000000009000L;
        for (long node : new long[]{OrgFixtures.A2, OrgFixtures.P, OrgFixtures.A3, OrgFixtures.T1}) {
            orgFixtures.grantResource(++seq, 1, courseIntact, node, OrgFixtures.ROOT);
        }
        orgFixtures.grantResource(++seq, 1, courseBroken, OrgFixtures.T1, OrgFixtures.A1);

        String token = loginAs(OrgFixtures.ROOT);
        JsonNode response = move(token, OrgFixtures.P, OrgFixtures.A2);

        assertThat(code(response)).isEqualTo(200);
        JsonNode grants = data(response).path("outOfScopeGrants");
        assertThat(data(response).path("outOfScopeGrantCount").asInt())
                .as("只有 A1 自有的那门课链断了：P 移到 A2 之下后，A1 既不在 T1 的祖先链上，"
                        + "链上也没有任何一层持有它。ROOT 那门逐级授到位的不算")
                .isEqualTo(1);
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).path("resourceId").asText()).isEqualTo(String.valueOf(courseBroken));
        assertThat(grants.get(0).path("targetNodeId").asText())
                .isEqualTo(String.valueOf(OrgFixtures.T1));
        assertThat(grants.get(0).path("resourceName").asText())
                .as("resourceName 在模块 06 恒为 null，模块 11 接手后才有值")
                .isEqualTo("原上级自有的课程");
        // 「不自动撤销」——否则每次转移都会静默中断学员正在学的课程
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM org_resource_grant WHERE target_node_id = ? AND deleted_at = 0",
                Integer.class, OrgFixtures.T1)).isEqualTo(2);
    }

    @Test
    @DisplayName("PRD F1-2：A 是 B 的祖先，把 A 移到 B 之下 → 10103，且两棵子树一律不变")
    void movingAncestorUnderItsDescendantIsRejectedAndChangesNothing() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);
        String pAncestorsBefore = orgFixtures.ancestorsOf(OrgFixtures.P);
        String s1AncestorsBefore = orgFixtures.ancestorsOf(OrgFixtures.S1);

        JsonNode response = move(token, OrgFixtures.P, OrgFixtures.T1);

        assertThat(code(response)).isEqualTo(10103);
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.P)).isEqualTo(pAncestorsBefore);
        assertThat(orgFixtures.ancestorsOf(OrgFixtures.S1)).isEqualTo(s1AncestorsBefore);
        assertThat(orgFixtures.parentOf(OrgFixtures.P)).isEqualTo(OrgFixtures.A1);
        assertThat(orgFixtures.parentOf(OrgFixtures.T1)).isEqualTo(OrgFixtures.A3);
        assertThat(orgFixtures.childCountOf(OrgFixtures.A1)).isEqualTo(1);
        assertThat(orgFixtures.changeLogCount(OrgFixtures.P)).isZero();
    }

    @Test
    @DisplayName("自环：把节点移到自己身上 → 10103（只判 FIND_IN_SET 那一半会漏掉它）")
    void movingNodeOntoItselfIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        // 节点自身的 ancestors 不含自己，所以「目标不能是自己的后代」这一条永远判为通过；
        // 挡住它的是另一条：targetParentId != movingId
        assertThat(code(move(token, OrgFixtures.P, OrgFixtures.P))).isEqualTo(10103);
        assertThat(orgFixtures.parentOf(OrgFixtures.P)).isEqualTo(OrgFixtures.A1);
    }

    /** 把嵌套的 {@code children} 拍平成一个列表。 */
    private static List<JsonNode> flatten(JsonNode nodes) {
        List<JsonNode> flat = new ArrayList<>();
        for (JsonNode node : nodes) {
            flat.add(node);
            flat.addAll(flatten(node.path("children")));
        }
        return flat;
    }
}
