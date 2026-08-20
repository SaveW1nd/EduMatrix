package com.edumatrix.org.grant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块 11 · C3：接口 37（我可授权的资源列表）与接口 41（节点已获授权资源列表）。
 *
 * <p>这两个接口<b>一行都不写</b>，但它们是整个模块最容易「不报错地错」的地方：
 * 少列一个资源、多列一个资源、把上级的清单漏给下级 —— 三种都返回 200。
 */
class GrantQueryIT extends GrantIntegrationTestBase {

    private static final String GRANTABLE = "/api/v1/org/grants/grantable-resources";

    private static String grantedOf(long nodeId) {
        return "/api/v1/org/nodes/" + nodeId + "/granted-resources";
    }

    // =====================================================================
    // 接口 37 §9.1
    // =====================================================================

    @Test
    @DisplayName("接口 37：自有 ∪ 受授权；上级拥有但未授予我的【一条都不出现】")
    void grantableIsOwnedUnionGranted() throws Exception {
        // ROOT 把 C1 授给 A1；C3 是 ROOT 自有但从未授出
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);

        JsonNode asRoot = getWithToken(GRANTABLE + "?resourceType=1", loginAs(GrantFixtures.ROOT));
        assertThat(code(asRoot)).isEqualTo(200);
        assertThat(resourceIds(asRoot))
                .as("ROOT 自有 C1 与 C3；C2 是 A1 自建的，ROOT【不该】看到 —— "
                        + "资源归属不随树向上汇总")
                .containsExactlyInAnyOrder(String.valueOf(GrantFixtures.C1),
                        String.valueOf(GrantFixtures.C3));

        JsonNode asA1 = getWithToken(GRANTABLE + "?resourceType=1", loginAs(GrantFixtures.A1));
        assertThat(resourceIds(asA1))
                .as("A1 = 自有 C2 ∪ 受授权 C1。C3 是 ROOT 拥有但从未授予 A1 的，"
                        + "按 PRD FR-2 规则 2「未授予的资源按不存在处理」，【绝不出现】")
                .containsExactlyInAnyOrder(String.valueOf(GrantFixtures.C1),
                        String.valueOf(GrantFixtures.C2));
    }

    @Test
    @DisplayName("接口 37：source=1/2 各切一半；source 与 validStart/validEnd 的语义按 §9.1")
    void grantableSourceAndValidity() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT,
                "2026-01-01 00:00:00", "2027-06-30 23:59:59");

        String token = loginAs(GrantFixtures.A1);
        JsonNode owned = getWithToken(GRANTABLE + "?resourceType=1&source=1", token);
        assertThat(resourceIds(owned)).containsExactly(String.valueOf(GrantFixtures.C2));
        assertThat(data(owned).path("list").get(0).path("validEnd").isNull())
                .as("§9.1 字段说明：自有资源 validStart/validEnd 为 null，表示【永久】")
                .isTrue();

        JsonNode granted = getWithToken(GRANTABLE + "?resourceType=1&source=2", token);
        assertThat(resourceIds(granted)).containsExactly(String.valueOf(GrantFixtures.C1));
        JsonNode row = data(granted).path("list").get(0);
        assertThat(row.path("validEnd").asText())
                .as("§9.1 字段说明：受授权资源的 validStart/validEnd 是【我自己持有该资源的有效期】，"
                        + "不是「这次要授出去的有效期」")
                .isEqualTo("2027-06-30 23:59:59");
        assertThat(row.path("ownerNodeName").asText()).isEqualTo("IT11 授权引擎机构");
        assertThat(row.path("extra").path("subject").asText()).isEqualTo("数学");
    }

    @Test
    @DisplayName("接口 37：三类资源都能查（resource_type 1/2/3 一次做全）")
    void grantableCoversAllThreeTypes() throws Exception {
        String token = loginAs(GrantFixtures.ROOT);
        assertThat(resourceIds(getWithToken(GRANTABLE + "?resourceType=2", token)))
                .containsExactly(String.valueOf(GrantFixtures.Q1));
        assertThat(resourceIds(getWithToken(GRANTABLE + "?resourceType=3", token)))
                .containsExactly(String.valueOf(GrantFixtures.V1));
    }

    @Test
    @DisplayName("接口 37：resourceType 非法 → 400（不是业务码）")
    void grantableRejectsBadType() throws Exception {
        assertThat(code(getWithToken(GRANTABLE + "?resourceType=9", loginAs(GrantFixtures.ROOT))))
                .isEqualTo(400);
        assertThat(code(getWithToken(GRANTABLE, loginAs(GrantFixtures.ROOT))))
                .as("resourceType 必填")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("⚠ 接口 37：跨管辖的受授权资源【不出现】—— 否则「看得见、授不出去」")
    void crossScopeResourceIsNotListed() throws Exception {
        // A2 把自己受授权的资源再往下发之前，先构造跨管辖：
        // ROOT 把 C1 授给 A1、A1 授给 T1，然后把 T1 整个挪到 A2 名下（A2 无 C1）
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        moveT1UnderA2();

        JsonNode asT1 = getWithToken(GRANTABLE + "?resourceType=1", loginAs(GrantFixtures.T1));
        assertThat(resourceIds(asT1))
                .as("T1 调岗到 A2 之后，C1 变成跨管辖授权：仍可【使用】，但丧失【再下发】能力"
                        + "（契约 §2.5 规则 9）。若它出现在这里，用户点了就会拿到 10301 —— "
                        + "界面在骗人，三种失败里最糟的一种")
                .isEmpty();
    }

    // =====================================================================
    // 接口 41 §9.5
    // =====================================================================

    @Test
    @DisplayName("接口 41：只返回【显式授予该节点】的行，不回溯祖先链")
    void grantedListDoesNotWalkUpTheChain() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C3, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);

        String token = loginAs(GrantFixtures.ROOT);
        assertThat(resourceIds(getWithToken(grantedOf(GrantFixtures.A1), token)))
                .containsExactlyInAnyOrder(String.valueOf(GrantFixtures.C1),
                        String.valueOf(GrantFixtures.C3));
        assertThat(resourceIds(getWithToken(grantedOf(GrantFixtures.T1), token)))
                .as("A1 有两门、T1 只有一门 —— 逐级收缩的直观体现。"
                        + "若这里出现 C3，说明有人回溯了祖先链（契约 §2.5 规则 4）")
                .containsExactly(String.valueOf(GrantFixtures.C1));
        assertThat(resourceIds(getWithToken(grantedOf(GrantFixtures.S[0]), token)))
                .as("教师有、学员没有 —— 每一跳都要显式授权（契约 §2.5 规则 3）")
                .isEmpty();
    }

    @Test
    @DisplayName("接口 41：响应字段齐全（资源名 / 授权人 / 来源 / 目标节点名）")
    void grantedRowIsFullyResolved() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);

        JsonNode row = data(getWithToken(grantedOf(GrantFixtures.A1), loginAs(GrantFixtures.ROOT)))
                .path("list").get(0);
        assertThat(row.path("resourceName").asText())
                .as("resourceName 在 crs_course 里，org 域读不到 —— 必须经 "
                        + "GrantableResourceProvider 取。模块 06 给它留的「恒为 null」由本模块补上")
                .isEqualTo("高三数学·函数与导数");
        assertThat(row.path("targetNodeName").asText()).isEqualTo("华东大区");
        assertThat(row.path("grantByName").asText()).isEqualTo("IT11 授权引擎机构");
        assertThat(row.path("grantSource").asInt()).isEqualTo(1);
        assertThat(row.path("grantSourceName").asText()).isEqualTo("手动选择");
        assertThat(row.path("expired").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("接口 41：includeExpired 默认排除过期行，置 true 时带出并标 expired")
    void grantedExpiredFiltering() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT,
                "2020-01-01 00:00:00", "2020-12-31 23:59:59");

        String token = loginAs(GrantFixtures.ROOT);
        assertThat(data(getWithToken(grantedOf(GrantFixtures.A1), token)).path("total").asInt())
                .as("默认只返回当前在有效期内的授权（§9.5 说明段）")
                .isZero();

        JsonNode withExpired = getWithToken(
                grantedOf(GrantFixtures.A1) + "?includeExpired=true", token);
        assertThat(data(withExpired).path("total").asInt()).isEqualTo(1);
        assertThat(data(withExpired).path("list").get(0).path("expired").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("接口 41：keyword 按【资源名】筛（名字在另一个领域的表里）")
    void grantedKeywordFiltersByResourceName() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C3, GrantFixtures.A1, GrantFixtures.ROOT);

        String token = loginAs(GrantFixtures.ROOT);
        // 裸中文直接进 URI —— 与 NodeTreeQueryIT 的现成写法一致。
        // 手动 URLEncoder 编码【反而不行】：MockMvc 不对查询串做百分号解码，
        // 服务端拿到的会是 %E5%87%BD%E6%95%B0 这串字面量，于是筛不出任何东西而接口仍返回 200
        assertThat(resourceIds(getWithToken(
                grantedOf(GrantFixtures.A1) + "?keyword=函数", token)))
                .containsExactly(String.valueOf(GrantFixtures.C1));
        assertThat(data(getWithToken(
                grantedOf(GrantFixtures.A1) + "?keyword=不存在的名字", token))
                .path("total").asInt()).isZero();
    }

    @Test
    @DisplayName("接口 41：越界返回 404（路径上的对象不暴露存在性）；教师查不到平级分支")
    void grantedOutOfSubtreeIs404() throws Exception {
        JsonNode resp = getWithToken(grantedOf(GrantFixtures.T2), loginAs(GrantFixtures.T1));
        assertThat(code(resp))
                .as("契约 §2.4 三分法：路径上的操作对象越界 → 404，不是 10107 也不是 403")
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("接口 41：学生能查自己（走 @SaCheckOr 的角色分支），查别人 404")
    void studentCanReadOwnNodeOnly() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.S[0], GrantFixtures.T1);

        String token = loginAs(GrantFixtures.S[0]);
        JsonNode mine = getWithToken(grantedOf(GrantFixtures.S[0]), token);
        assertThat(code(mine))
                .as("学生在 sys_role_menu 里一行绑定都没有（契约 §3.1 边界 0），"
                        + "单个 @SaCheckPermission 会让他拿 403")
                .isEqualTo(200);
        assertThat(resourceIds(mine)).containsExactly(String.valueOf(GrantFixtures.C1));

        assertThat(code(getWithToken(grantedOf(GrantFixtures.S[1]), token)))
                .as("同班同学也不行 —— 学生的子树就是他自己")
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("接口 41：跨租户 404")
    void crossTenantIs404() throws Exception {
        assertThat(code(getWithToken(grantedOf(GrantFixtures.ROOT2), loginAs(GrantFixtures.ROOT))))
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    // ================================================================ 辅助

    /** 把 T1 挪到 A2 名下（不经接口，直接改树 —— 本类不测移动，只要那个树形）。 */
    private void moveT1UnderA2() {
        String underA2 = "0," + GrantFixtures.ROOT + "," + GrantFixtures.A2;
        jdbcTemplate.update("UPDATE org_node SET parent_id = ?, ancestors = ? WHERE id = ?",
                GrantFixtures.A2, underA2, GrantFixtures.T1);
        jdbcTemplate.update("UPDATE org_node SET ancestors = ? WHERE parent_id = ?",
                underA2 + "," + GrantFixtures.T1, GrantFixtures.T1);
        cleanGrantRedisKeys();
    }
}
