package com.edumatrix.question.support;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 模块 10 集成测试的公共底座。
 *
 * <p>继承 {@code AuthIntegrationTestBase} 的理由与模块 06 / 07 / 08 逐字相同：
 * {@code TenantHelper} 的 provider 是<b>静态字段</b>，Spring 测试上下文按配置缓存 ——
 * 一旦本模块另起一个上下文，先前上下文里的测试类再跑就会读到别人的 provider。
 * 所以不加 {@code @TestConfiguration}、不用 {@code @MockBean}。
 *
 * <p><b>全部用例走真实登录</b>：本模块的一半内容是题目可见性
 * （{@code owner_node_id} ∪ 授权）与写权限（403），用测试 provider 直接设会话
 * 会把这一半绕过去。
 */
public abstract class QuestionIntegrationTestBase extends AuthIntegrationTestBase {

    protected QuestionFixtures questionFixtures;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpQuestionBase() {
        questionFixtures = new QuestionFixtures(jdbcTemplate);
        questionFixtures.seed();
        cleanQuestionRedisKeys();
    }

    @AfterEach
    void tearDownQuestionBase() {
        cleanQuestionRedisKeys();
        questionFixtures.clean();
    }

    /** 逐个删本模块用到的祖先链缓存键，不用 FLUSHDB（库里还有别的模块的键）。 */
    protected void cleanQuestionRedisKeys() {
        for (long nodeId : new long[]{QuestionFixtures.ROOT, QuestionFixtures.A1,
                QuestionFixtures.TA, QuestionFixtures.TB, QuestionFixtures.ROOT2}) {
            redisTemplate.delete(RedisKeys.nodeAncestors(nodeId));
        }
        redisTemplate.delete(RedisKeys.frozenSet(QuestionFixtures.TENANT_ID));
        redisTemplate.delete(RedisKeys.frozenSet(QuestionFixtures.TENANT2_ID));
    }

    protected String loginAs(long nodeId) throws Exception {
        return client.loginForToken(QuestionFixtures.usernameOf(nodeId), QuestionFixtures.PASSWORD);
    }

    /**
     * 以「测试 provider 模拟的会话」直接调 Service 跑一段代码。
     *
     * <p><b>只给还没有 Controller 的那一层用</b>（C3 的可见性 / 版本服务）。
     * 一旦接口存在，用例一律走真实登录 —— 用测试 provider 设会话会把
     * {@code @SaCheckPermission} 与真实的会话装配整条绕过去。
     *
     * <p>跑完<b>必须切回</b> Sa-Token provider，否则同一个 Spring 上下文里
     * 后续那些验真实登录的用例会读到测试 provider —— 那正是
     * {@code AuthIntegrationTestBase} 类注释警告的那件事。
     */
    protected void runAsTestUser(long tenantId, long nodeId, ThrowingRunnable action)
            throws Exception {
        testContextProvider.asTenantUser(tenantId, QuestionFixtures.userIdOf(nodeId), nodeId);
        com.edumatrix.common.tenant.TenantHelper.setProvider(testContextProvider);
        try {
            action.run();
        } finally {
            com.edumatrix.common.tenant.TenantHelper.setProvider(saTokenContextProvider);
            testContextProvider.asNoSession();
        }
    }

    /** {@link #runAsTestUser} 的动作类型（{@code Runnable} 不能抛检查异常）。 */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
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

    protected JsonNode deleteWithToken(String path, String token) throws Exception {
        return exchange(delete(path), token, null);
    }

    private JsonNode exchange(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                      request, String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(body);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(content.isBlank() ? "{}" : content);
    }

    protected static int code(JsonNode response) {
        return response.path("code").asInt();
    }

    protected static JsonNode data(JsonNode response) {
        return response.path("data");
    }

    /**
     * 一次请求的完整结果：HTTP 状态码 + 响应体业务码（模块 08 的 F-42 形态）。
     *
     * <p>「不存在」与「不可见」必须给出<b>同一个响应</b>，所以要比的是<b>两次响应本身</b>，
     * 而不是各自等于某个常量。{@code record} 自带 {@code equals}，
     * 于是这件事可以用一句 {@code assertEquals(a, b)} 表达。
     */
    public record HttpOutcome(int httpStatus, int bizCode) {
    }

    protected HttpOutcome outcome(String method, String path, String token, String body)
            throws Exception {
        var request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(body);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(content.isBlank() ? "{}" : content);
        return new HttpOutcome(result.getResponse().getStatus(), json.path("code").asInt());
    }

    /**
     * 某个节点上的账号<b>实际拿到的 perms</b>（走 {@code /auth/me}，
     * 即 {@code sys_role_menu → sys_menu.perms} 那条真实链路）。
     *
     * <h2>为什么需要它 —— F-114 收窄之后「教师 403」不再能证明 F-72</h2>
     * <p>写端点上现在压着两道闸：{@code @SaCheckPermission}（权限位）与
     * {@code OrgRootGuard}（在不在机构根）。教师两道都过不了，<b>403 说明不了是哪一道</b>。
     * 实测（M62）：把撤掉的绑定加回 {@code sys_role_menu}，那些「教师 403」的用例<b>照样全绿</b>。
     * 要断 F-72 说的那个「真相」，判据必须落在 perms 本身上。
     */
    protected java.util.List<String> permsOf(long nodeId) throws Exception {
        JsonNode me = getWithToken("/api/v1/auth/me", loginAs(nodeId));
        java.util.List<String> perms = new java.util.ArrayList<>();
        data(me).path("perms").forEach(node -> perms.add(node.asText()));
        return perms;
    }
}
