package com.edumatrix.org;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.support.OrgFixtures;
import com.edumatrix.org.support.OrgIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 03-02 §3.1 组织树查询与 §3.2 节点详情。
 *
 * <p>{@code parentId} / {@code deep} 两个参数只在 §3.1 的说明段里、不在参数表里
 * （分册自身不一致，已登记为 F-26）。这里按说明段验，包括它给出的三条硬约束：
 * {@code deep=true} 必须同时传 {@code maxDepth} 或 {@code nodeTypes}、
 * 硬上限 2000 个节点、超管禁止以平台根为起点。
 */
class NodeTreeQueryIT extends OrgIntegrationTestBase {

    @Test
    @DisplayName("§3.1 默认懒加载：不传 parentId 只返回调用者所在节点的直接子节点")
    void defaultIsLazyOneLevel() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode data = data(client.getWithToken("/api/v1/org/nodes/tree", token));

        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("children")).isEmpty();
        assertThat(data.get(1).path("children")).isEmpty();
        // refUserName「恒非空」（§3.1 响应字段说明）
        assertThat(data.get(0).path("refUserName").asText()).isNotBlank();
        // 冗余计数照原样回传：A1 有 3 个教师、A2 有 1 个
        assertThat(data.get(0).path("childCount").asInt() + data.get(1).path("childCount").asInt())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("§3.1 展开一层：带 parentId 时返回它的直接子节点")
    void expandOneLevelByParentId() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode data = data(client.getWithToken(
                "/api/v1/org/nodes/tree?parentId=" + OrgFixtures.A1, token));

        assertThat(data).hasSize(3);
        assertThat(data.get(0).path("id").asText()).isEqualTo(String.valueOf(OrgFixtures.T1));
    }

    @Test
    @DisplayName("§3.1 deep=true 未同时传 maxDepth 或 nodeTypes → 400")
    void deepWithoutNarrowingIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode response = client.getWithToken("/api/v1/org/nodes/tree?deep=true", token);

        assertThat(code(response)).isEqualTo(400);
    }

    @Test
    @DisplayName("§3.1 nodeTypes 筛选：被排除的节点其子节点一并不返回（保持树的连通性）")
    void filteredOutBranchesAreDroppedEntirely() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode data = data(client.getWithToken(
                "/api/v1/org/nodes/tree?deep=true&nodeTypes=1", token));

        // 只画到管理员层：A1、A2；教师与学生整支不返回。
        // F-114 之后管理员只有一层，所以这里断言的是【它们没有管理员子节点】——
        // 原先这条走的是 A1 → P → A3 三层管理员，那种形状现在建不出来
        assertThat(data).hasSize(2);
        JsonNode a1 = data.get(0).path("id").asText().equals(String.valueOf(OrgFixtures.A1))
                ? data.get(0) : data.get(1);
        assertThat(a1.path("id").asText()).isEqualTo(String.valueOf(OrgFixtures.A1));
        // A1 下的三个教师被 nodeTypes=1 排除，它们的学员也就整支不返回
        assertThat(a1.path("children")).isEmpty();
    }

    @Test
    @DisplayName("§3.1 maxDepth：相对树根的深度上限")
    void maxDepthLimitsRelativeDepth() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode data = data(client.getWithToken(
                "/api/v1/org/nodes/tree?deep=true&maxDepth=1", token));

        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("children")).isEmpty();
    }

    @Test
    @DisplayName("§3.1 keyword：命中节点及其全部祖先链一并返回，未命中分支不返回")
    void keywordReturnsHitsWithTheirAncestorChain() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode data = data(client.getWithToken("/api/v1/org/nodes/tree?keyword=学生一", token));

        // 命中 S1，链路 A1 → T1 → S1 一并回来；A2 那一支完全不返回
        assertThat(data).hasSize(1);
        JsonNode a1 = data.get(0);
        assertThat(a1.path("id").asText()).isEqualTo(String.valueOf(OrgFixtures.A1));
        assertThat(a1.path("children")).hasSize(1);
        JsonNode t1 = a1.path("children").get(0);
        assertThat(t1.path("id").asText()).isEqualTo(String.valueOf(OrgFixtures.T1));
        JsonNode s1 = t1.path("children").get(0);
        assertThat(s1.path("id").asText()).isEqualTo(String.valueOf(OrgFixtures.S1));
    }

    @Test
    @DisplayName("§3.1 数据权限：rootId 不在我的子树内 → 10107（我选的目标越界）")
    void rootIdOutsideMySubtreeIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.A2);

        JsonNode response = client.getWithToken(
                "/api/v1/org/nodes/tree?rootId=" + OrgFixtures.A1, token);

        assertThat(code(response)).isEqualTo(10107);
    }

    @Test
    @DisplayName("§3.1 教师调用时树根即其教师节点，返回的子树就是名下学员列表")
    void teacherSeesOnlyHisOwnStudents() throws Exception {
        String token = loginAs(OrgFixtures.T3);

        JsonNode data = data(client.getWithToken("/api/v1/org/nodes/tree", token));

        assertThat(data).hasSize(3);
        for (JsonNode child : data) {
            assertThat(child.path("nodeType").asInt()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("§3.1 includeDisabled 默认 false：已停用节点不返回")
    void disabledNodesAreHiddenByDefault() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);
        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.A2 + "/status",
                token, "{\"status\":1}"))).isEqualTo(200);

        assertThat(data(client.getWithToken("/api/v1/org/nodes/tree", token))).hasSize(1);
        assertThat(data(client.getWithToken("/api/v1/org/nodes/tree?includeDisabled=true", token)))
                .hasSize(2);
    }

    @Test
    @DisplayName("§3.2 节点详情：面包屑自租户根起（不含平台根哨兵）、childStat 只数直接子节点")
    void detailReturnsBreadcrumbAndChildStat() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode data = data(client.getWithToken("/api/v1/org/nodes/" + OrgFixtures.A1, token));

        assertThat(data.path("parentName").asText()).isEqualTo("IT06 组织树机构");
        // 路径：ROOT → A1。平台根哨兵 0 【不在】里面（契约 §2.9）。
        // F-114 之后合法树最深就是 ROOT → A1 → T → S，面包屑因此最长 4 段
        JsonNode path = data.path("path");
        assertThat(path).hasSize(2);
        assertThat(path.get(0).path("id").asText()).isEqualTo(String.valueOf(OrgFixtures.ROOT));
        assertThat(path.get(1).path("id").asText()).isEqualTo(String.valueOf(OrgFixtures.A1));
        // A1 的直接子节点是 T1 / T2 / T3 三个教师；它们底下的 8 个学生【不算】
        assertThat(data.path("childStat").path("teacherCount").asInt()).isEqualTo(3);
        assertThat(data.path("childStat").path("studentCount").asInt()).isZero();
        assertThat(data.path("childStat").path("orgCount").asInt()).isZero();
        // studentCount 是【整棵子树】的在读学生数
        assertThat(data.path("studentCount").asInt()).isEqualTo(8);
    }

    @Test
    @DisplayName("§3.2 grantedResourceStat 只数授权给本节点的行，不回溯祖先链")
    void grantedResourceStatDoesNotWalkUpTheChain() throws Exception {
        orgFixtures.grantResource(1962000000000009011L, 1, 1957000000000000001L,
                OrgFixtures.T1, OrgFixtures.ROOT);
        orgFixtures.grantResource(1962000000000009012L, 3, 1959000000000000001L,
                OrgFixtures.T1, OrgFixtures.ROOT);
        // 授给 T1 的上级 A1 —— 契约 §2.5 规则 3「不向下继承」，不该出现在 T1 的统计里
        orgFixtures.grantResource(1962000000000009013L, 2, 1958000000000000001L,
                OrgFixtures.A1, OrgFixtures.ROOT);
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode stat = data(client.getWithToken("/api/v1/org/nodes/" + OrgFixtures.T1, token))
                .path("grantedResourceStat");

        assertThat(stat.path("courseCount").asInt()).isEqualTo(1);
        assertThat(stat.path("videoCount").asInt()).isEqualTo(1);
        assertThat(stat.path("questionCount").asInt()).isZero();
    }

    @Test
    @DisplayName("§3.2 数据权限：目标不在子树内 → 404（不暴露存在性）")
    void detailOutsideMySubtreeReturns404() throws Exception {
        String token = loginAs(OrgFixtures.A2);

        var result = client.getRawWithToken("/api/v1/org/nodes/" + OrgFixtures.T1, token);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("§3.3 修改节点：改名同步 sys_user.real_name，同级重名 → 10102")
    void updateSyncsRealNameAndRejectsDuplicates() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.T1, token,
                "{\"nodeName\":\"王丽丽\",\"sort\":3,\"remark\":\"改名\"}"))).isEqualTo(200);
        assertThat(orgFixtures.realNameOf(OrgFixtures.T1)).isEqualTo("王丽丽");

        // T2 与 T1 同父，改成同名要拒
        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.T2, token,
                "{\"nodeName\":\"王丽丽\"}"))).isEqualTo(10102);
    }
}
