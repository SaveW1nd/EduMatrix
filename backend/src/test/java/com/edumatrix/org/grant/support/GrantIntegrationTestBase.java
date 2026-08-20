package com.edumatrix.org.grant.support;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.edumatrix.common.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 模块 11 集成测试的公共底座。
 *
 * <p>继承 {@code AuthIntegrationTestBase} 的理由与模块 06 / 07 / 08 逐字相同：
 * {@code TenantHelper} 的 provider 是<b>静态字段</b>，Spring 测试上下文按配置缓存 ——
 * 另起一个上下文会让先前上下文里的测试类读到别人的 provider。
 *
 * <p><b>全部用例走真实登录</b>：本模块的主体就是权限判定（三个维度、四个错误码），
 * 用测试 provider 直接设会话会把 {@code @SaCheckPermission} 与真实会话装配整条绕过去，
 * 于是「注解通过 ≠ 有权授权」这条<b>根本测不到</b>。
 */
public abstract class GrantIntegrationTestBase extends AuthIntegrationTestBase {

    protected GrantFixtures grantFixtures;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpGrantBase() {
        grantFixtures = new GrantFixtures(jdbcTemplate);
        grantFixtures.seed();
        cleanGrantRedisKeys();
    }

    @AfterEach
    void tearDownGrantBase() {
        cleanGrantRedisKeys();
        // setUp 失败时本方法仍会执行，而那时 grantFixtures 可能还没被赋值。
        // 不加空判的话，这里的 NPE 会把【真正的失败原因】埋掉
        if (grantFixtures != null) {
            grantFixtures.clean();
        }
    }

    /** 逐个删本模块用到的祖先链缓存键；不用 FLUSHDB（库里还有别的模块的键）。 */
    protected void cleanGrantRedisKeys() {
        for (long nodeId : GrantFixtures.ALL_NODES) {
            redisTemplate.delete(RedisKeys.nodeAncestors(nodeId));
        }
        redisTemplate.delete(RedisKeys.frozenSet(GrantFixtures.TENANT_ID));
        redisTemplate.delete(RedisKeys.frozenSet(GrantFixtures.TENANT2_ID));
    }

    protected String loginAs(long nodeId) throws Exception {
        return client.loginForToken(GrantFixtures.usernameOf(nodeId), GrantFixtures.PASSWORD);
    }

    // ================================================================ 请求

    protected JsonNode getWithToken(String path, String token) throws Exception {
        return exchange(get(path), token, null);
    }

    protected JsonNode postWithToken(String path, String token, String body) throws Exception {
        return exchange(post(path), token, body);
    }

    protected JsonNode putWithToken(String path, String token, String body) throws Exception {
        return exchange(put(path), token, body);
    }

    protected JsonNode deleteWithToken(String path, String token, String body) throws Exception {
        return exchange(delete(path), token, body);
    }

    private JsonNode exchange(MockHttpServletRequestBuilder builder, String token, String body)
            throws Exception {
        builder.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(body);
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(content.isBlank() ? "{}" : content);
    }

    // ================================================================ 断言辅助

    protected static int code(JsonNode response) {
        return response.path("code").asInt();
    }

    protected static JsonNode data(JsonNode response) {
        return response.path("data");
    }

    /** 分页响应里的资源 ID 列表（{@code data.list[].resourceId}）。 */
    protected static java.util.List<String> resourceIds(JsonNode response) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        data(response).path("list").forEach(row -> ids.add(row.path("resourceId").asText()));
        return ids;
    }
}
