package com.edumatrix.org.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.edumatrix.common.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 模块 06 集成测试的公共底座。
 *
 * <h2>为什么继承模块 02 的底座</h2>
 * <p>{@code AuthIntegrationTestBase} 的类注释把理由写死了：{@code TenantHelper} 的 provider
 * 是<b>静态字段</b>，而 Spring 的测试上下文按配置缓存 —— 每建一个新上下文就会重新执行
 * {@code TenantConfig.tenantHelperInitializer}，把那个静态字段指向新上下文的 provider，
 * 于是先前上下文里的测试类再跑就会读到别人的 provider。
 * 所以本模块的 IT 一律沿用同一个上下文配置：不加 {@code @TestConfiguration}、不用
 * {@code @MockBean}。继承它也顺带拿到了真实登录客户端 {@code AuthTestClient}。
 *
 * <p>本模块的用例<b>全部走真实登录</b>：数据权限（子树判定）与角色（{@code perms}）
 * 是这六个接口的一半内容，用测试 provider 直接设一个会话会把这一半绕过去。
 */
public abstract class OrgIntegrationTestBase extends AuthIntegrationTestBase {

    protected OrgFixtures orgFixtures;

    @BeforeEach
    void setUpOrgBase() {
        orgFixtures = new OrgFixtures(jdbcTemplate);
        orgFixtures.seed();
        cleanOrgRedisKeys();
    }

    @AfterEach
    void tearDownOrgBase() {
        cleanOrgRedisKeys();
        // setUp 失败时本方法【仍会执行】，而那时 orgFixtures 可能还没被赋值。
        // 不加空判的话，这里抛出的 NPE 会把【真正的失败原因】埋在下面 ——
        // 排查的人第一眼看到的是 NullPointerException，而不是那条 Duplicate entry。
        // 这不是吞异常：setUp 的原始异常照常上报，本判定只是不再往上面加噪声。
        if (orgFixtures != null) {
            orgFixtures.clean();
        }
    }

    /**
     * 清掉本模块用到的 key。
     *
     * <p>逐个删而不是 {@code FLUSHDB}：测试库里还有模块 01/02 的键，
     * 整库清会让那批用例的缓存假设失效。
     */
    protected void cleanOrgRedisKeys() {
        redisTemplate.delete(RedisKeys.frozenSet(OrgFixtures.TENANT_ID));
        for (long nodeId : OrgFixtures.ALL_NODES) {
            redisTemplate.delete(RedisKeys.nodeAncestors(nodeId));
        }
    }

    /** 以某个夹具节点的账号登录，返回 accessToken。 */
    protected String loginAs(long nodeId) throws Exception {
        return client.loginForToken(OrgFixtures.usernameOf(nodeId), OrgFixtures.PASSWORD);
    }

    protected static int code(JsonNode response) {
        return response.path("code").asInt();
    }

    protected static JsonNode data(JsonNode response) {
        return response.path("data");
    }

    /** 组一个移动请求体。 */
    /**
     * 常规移动请求体。
     *
     * <p><b>{@code revokeOutOfScopeGrants} 必须显式带上</b>（F-114 定案三：必填、无默认值），
     * 这里统一带 {@code false} —— 绝大多数用例验的是树结构本身，与回不回收无关，
     * 而 {@code false} 保持了这些用例改动前的行为。
     * <b>「不传会被拒」由 {@code NodeMoveOutOfScopeGrantIT#omittingTheChoiceIsRejected} 单独守着</b>，
     * 不要在本方法里把它兜掉。
     */
    protected static String moveBody(long toParentId) {
        return "{\"toParentId\":\"" + toParentId + "\",\"revokeOutOfScopeGrants\":false}";
    }

    protected static String moveBody(long toParentId, String reason) {
        return "{\"toParentId\":\"" + toParentId + "\",\"revokeOutOfScopeGrants\":false,"
                + "\"reason\":\"" + reason + "\"}";
    }

    /** {@code PUT /org/nodes/{id}/move}。 */
    protected JsonNode move(String token, long nodeId, long toParentId) throws Exception {
        return client.putWithToken("/api/v1/org/nodes/" + nodeId + "/move", token,
                moveBody(toParentId));
    }
}
