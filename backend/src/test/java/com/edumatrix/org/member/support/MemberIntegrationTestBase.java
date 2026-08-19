package com.edumatrix.org.member.support;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.edumatrix.common.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 模块 07 集成测试的公共底座。
 *
 * <p>继承 {@code AuthIntegrationTestBase} 的理由与模块 06 逐字相同：{@code TenantHelper}
 * 的 provider 是<b>静态字段</b>，Spring 测试上下文按配置缓存 —— 一旦本模块另起一个上下文，
 * 先前上下文里的测试类再跑就会读到别人的 provider。所以不加 {@code @TestConfiguration}、
 * 不用 {@code @MockBean}。
 *
 * <p><b>全部用例走真实登录</b>：本模块的一半内容是数据权限（子树）与角色（{@code perms}），
 * 用测试 provider 直接设会话会把这一半绕过去。
 */
public abstract class MemberIntegrationTestBase extends AuthIntegrationTestBase {

    protected MemberFixtures memberFixtures;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpMemberBase() {
        memberFixtures = new MemberFixtures(jdbcTemplate);
        memberFixtures.seed();
        cleanMemberRedisKeys();
    }

    @AfterEach
    void tearDownMemberBase() {
        cleanMemberRedisKeys();
        // setUp 失败时本方法【仍会执行】，而那时 memberFixtures 可能还没被赋值。
        // 不加空判的话，这里抛出的 NPE 会把【真正的失败原因】埋在下面 ——
        // 排查的人第一眼看到的是 NullPointerException，而不是那条 Duplicate entry。
        // 这不是吞异常：setUp 的原始异常照常上报，本判定只是不再往上面加噪声。
        if (memberFixtures != null) {
            memberFixtures.clean();
        }
    }

    /**
     * 清掉本模块用到的祖先链缓存键。
     *
     * <p>逐个删而不是 {@code FLUSHDB}：测试库里还有模块 01/02/06 的键。
     * 本模块<b>会建人</b>，新节点的 id 是雪花生成的、事先不知道 —— 但它们的缓存键
     * 只在用例内产生，且用例结束时整棵树都被删了，回源查不到即失效，不会污染别人。
     */
    protected void cleanMemberRedisKeys() {
        redisTemplate.delete(RedisKeys.frozenSet(MemberFixtures.TENANT_ID));
        redisTemplate.delete(RedisKeys.nodeAncestors(MemberFixtures.ROOT));
        redisTemplate.delete(RedisKeys.nodeAncestors(MemberFixtures.A1));
        redisTemplate.delete(RedisKeys.nodeAncestors(MemberFixtures.A2));
        redisTemplate.delete(RedisKeys.nodeAncestors(MemberFixtures.T1));
        redisTemplate.delete(RedisKeys.nodeAncestors(MemberFixtures.T2));
        for (long nodeId : MemberFixtures.STUDENTS) {
            redisTemplate.delete(RedisKeys.nodeAncestors(nodeId));
        }
    }

    protected String loginAsRoot() throws Exception {
        return client.loginForToken(MemberFixtures.ROOT_USERNAME, MemberFixtures.PASSWORD);
    }

    protected String loginAs(long nodeId) throws Exception {
        return client.loginForToken(MemberFixtures.usernameOf(nodeId), MemberFixtures.PASSWORD);
    }

    /** {@code AuthTestClient} 没有 DELETE（模块 02 用不到），在这里补一个，不改它。 */
    protected JsonNode deleteWithToken(String path, String token) throws Exception {
        MvcResult result = mockMvc.perform(delete(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)).andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(content.isBlank() ? "{}" : content);
    }

    protected static int code(JsonNode response) {
        return response.path("code").asInt();
    }

    protected static JsonNode data(JsonNode response) {
        return response.path("data");
    }
}
