package com.edumatrix.course.catalog.support;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.edumatrix.common.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 模块 08 集成测试的公共底座。
 *
 * <p>继承 {@code AuthIntegrationTestBase} 的理由与模块 06 / 07 逐字相同：
 * {@code TenantHelper} 的 provider 是<b>静态字段</b>，Spring 测试上下文按配置缓存 ——
 * 一旦本模块另起一个上下文，先前上下文里的测试类再跑就会读到别人的 provider。
 * 所以不加 {@code @TestConfiguration}、不用 {@code @MockBean}。
 *
 * <p><b>全部用例走真实登录</b>：本模块的一半内容是资源可见性（{@code owner_node_id} ∪ 授权）
 * 与编排权限（403），用测试 provider 直接设会话会把这一半绕过去。
 */
public abstract class CourseIntegrationTestBase extends AuthIntegrationTestBase {

    protected CourseFixtures courseFixtures;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUpCourseBase() {
        courseFixtures = new CourseFixtures(jdbcTemplate);
        courseFixtures.seed();
        cleanCourseRedisKeys();
    }

    @AfterEach
    void tearDownCourseBase() {
        cleanCourseRedisKeys();
        // setUp 失败时本方法【仍会执行】，而那时 courseFixtures 可能还没被赋值。
        // 不加空判的话，这里抛出的 NPE 会把【真正的失败原因】埋在下面 ——
        // 排查的人第一眼看到的是 NullPointerException，而不是那条 Duplicate entry。
        // 这不是吞异常：setUp 的原始异常照常上报，本判定只是不再往上面加噪声。
        if (courseFixtures != null) {
            courseFixtures.clean();
        }
    }

    /** 清掉本模块用到的祖先链缓存键；逐个删而不是 FLUSHDB（库里还有别的模块的键）。 */
    protected void cleanCourseRedisKeys() {
        for (long nodeId : new long[]{CourseFixtures.ROOT, CourseFixtures.A1, CourseFixtures.TA,
                CourseFixtures.TB, CourseFixtures.ROOT2}) {
            redisTemplate.delete(RedisKeys.nodeAncestors(nodeId));
        }
        redisTemplate.delete(RedisKeys.frozenSet(CourseFixtures.TENANT_ID));
        redisTemplate.delete(RedisKeys.frozenSet(CourseFixtures.TENANT2_ID));
    }

    protected String loginAs(long nodeId) throws Exception {
        return client.loginForToken(CourseFixtures.usernameOf(nodeId), CourseFixtures.PASSWORD);
    }

    /** 按用户名登录 —— 用例自己种的、不走 {@code usernameOf(nodeId)} 规则的账号用它。 */
    protected String loginAsUsername(String username) throws Exception {
        return client.loginForToken(username, CourseFixtures.PASSWORD);
    }

    /** 平台超管 —— 用于「超管不参与本模块业务操作」那条断言（03-03 §0.2）。 */
    protected String loginAsSuperAdmin() throws Exception {
        return client.loginForToken(AuthFixtures.SUPER_ADMIN_USERNAME, AuthFixtures.PASSWORD);
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

    /**
     * 以「测试 provider 模拟的会话」在<b>多线程</b>里跑一段代码。
     *
     * <p><b>并发用例专用</b>：真实登录的会话由 Sa-Token 从请求上下文取，
     * 而 {@code MockMvc} 的上下文是线程绑定的 —— 从别的线程发请求拿不到它。
     * 所以并发用例改为<b>直接调 Service</b>，会话由静态门面上的测试 provider 提供
     * （两个线程模拟同一个用户，共享字段没有竞态）。
     *
     * <p>跑完<b>必须切回</b> Sa-Token provider，否则同一个 Spring 上下文里
     * 后续那些验真实登录的用例会读到测试 provider —— 那正是
     * {@code AuthIntegrationTestBase} 类注释警告的那件事。
     */
    protected void runAsTestUser(long tenantId, long nodeId, ThrowingRunnable action) throws Exception {
        testContextProvider.asTenantUser(tenantId, CourseFixtures.userIdOf(nodeId), nodeId);
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

    // =====================================================================
    // F-42：探测不出存在性 —— 要比的是【两次响应本身】
    // =====================================================================

    /**
     * 一次请求的完整结果：HTTP 状态码 + 响应体业务码。
     *
     * <p>{@code record} 自带 {@code equals}，于是「两次响应完全一致」可以用一句
     * {@code assertEquals(a, b)} 表达，失败信息还会把两边都打出来。
     */
    public record HttpOutcome(int httpStatus, int bizCode) {
    }

    protected HttpOutcome outcome(String method, String path, String token, String body)
            throws Exception {
        var request = switch (method) {
            case "GET" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
            case "PUT" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path);
            case "DELETE" ->
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path);
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
        JsonNode me = client.getWithToken("/api/v1/auth/me", loginAs(nodeId));
        java.util.List<String> perms = new java.util.ArrayList<>();
        data(me).path("perms").forEach(node -> perms.add(node.asText()));
        return perms;
    }
}
