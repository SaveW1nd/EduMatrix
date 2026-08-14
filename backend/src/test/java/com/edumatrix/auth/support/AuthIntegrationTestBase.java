package com.edumatrix.auth.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.edumatrix.auth.session.SaTokenCurrentContextProvider;
import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestCurrentContextProvider;

/**
 * 模块 02 集成测试的公共底座。
 *
 * <h2>为什么复用模块 01 的 {@code @IntegrationTest} 而不是自定义一个</h2>
 * <p>{@code TenantHelper} 的 provider 是<b>静态字段</b>（它要被无 Spring 上下文的地方
 * ——MyBatis 拦截器内部——直接调用）。Spring 的测试上下文按配置缓存，
 * <b>每建一个新上下文就会重新执行 {@code TenantConfig.tenantHelperInitializer}</b>，
 * 把那个静态字段指向新上下文的 provider。于是「第二个上下文」一旦出现，
 * 先前上下文里的测试类再跑（用的是缓存上下文、但静态字段已被改走）就会读到别人的 provider。
 *
 * <p>所以本模块的 IT <b>一律沿用同一个上下文配置</b>：不加 {@code @TestConfiguration}、
 * 不用 {@code @MockBean}，任何会改变上下文缓存键的东西都不用。
 *
 * <h2>provider 的临时切换</h2>
 * <p>模块 01 的 {@code TestSupportConfiguration} 注册了 {@code @Primary} 的
 * {@code TestCurrentContextProvider}（它要在没有登录接口时也能模拟会话）。
 * 而本模块要验的恰恰是<b>真实的 Sa-Token 会话</b>，所以每个用例前把静态门面切到
 * {@link SaTokenCurrentContextProvider}，用例后<b>切回同一上下文里的那个测试 provider</b> ——
 * 切回的是同一个实例，模块 01 的用例因此不受影响。
 */
@IntegrationTest
public abstract class AuthIntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected SaTokenCurrentContextProvider saTokenContextProvider;

    @Autowired
    protected TestCurrentContextProvider testContextProvider;

    protected AuthFixtures fixtures;
    protected AuthTestClient client;

    @BeforeEach
    void setUpAuthBase() {
        fixtures = new AuthFixtures(jdbcTemplate);
        client = new AuthTestClient(mockMvc, redisTemplate);
        fixtures.seed();
        cleanAuthRedisKeys();
        TenantHelper.setProvider(saTokenContextProvider);
    }

    @AfterEach
    void tearDownAuthBase() {
        fixtures.clean();
        cleanAuthRedisKeys();
        TenantHelper.setProvider(testContextProvider);
        testContextProvider.asNoSession();
        TenantHelper.reset();
    }

    /**
     * 清掉本模块用到的全部 key。
     *
     * <p>逐个删而不是 {@code FLUSHDB}：测试库（{@code database: 1}）里还有模块 01 的
     * {@code node:anc:*}，整库清会让那批用例的缓存假设失效 —— 虽然它们会回源查库，
     * 但那就不再是它们想验的东西了。
     */
    protected void cleanAuthRedisKeys() {
        redisTemplate.delete(RedisKeys.frozenSet(AuthFixtures.TENANT_ID));
        redisTemplate.delete(RedisKeys.frozenSet(AuthFixtures.EXPIRED_TENANT_ID));
        redisTemplate.delete(RedisKeys.rateLimitLogin("127.0.0.1"));
        redisTemplate.delete(RedisKeys.rateLimitCaptcha("127.0.0.1"));
        for (long nodeId : new long[]{
                AuthFixtures.ROOT_NODE, AuthFixtures.ADMIN_NODE, AuthFixtures.STUDENT1_NODE,
                AuthFixtures.TEACHER_NODE, AuthFixtures.STUDENT2_NODE,
                AuthFixtures.EXPIRED_ROOT_NODE, 0L}) {
            redisTemplate.delete(RedisKeys.nodeAncestors(nodeId));
        }
        for (String username : new String[]{
                AuthFixtures.ROOT_USERNAME, AuthFixtures.ADMIN_USERNAME,
                AuthFixtures.STUDENT1_USERNAME, AuthFixtures.TEACHER_USERNAME,
                AuthFixtures.STUDENT2_USERNAME, AuthFixtures.EXPIRED_USERNAME,
                AuthFixtures.SUPER_ADMIN_USERNAME}) {
            redisTemplate.delete(RedisKeys.loginFail(username));
            redisTemplate.delete(RedisKeys.loginLock(username));
        }
        for (long userId : new long[]{
                AuthFixtures.ROOT_USER, AuthFixtures.ADMIN_USER, AuthFixtures.STUDENT1_USER,
                AuthFixtures.TEACHER_USER, AuthFixtures.STUDENT2_USER,
                AuthFixtures.EXPIRED_ADMIN_USER, AuthFixtures.SUPER_ADMIN_USER}) {
            redisTemplate.delete(RedisKeys.refreshTokenUserIndex(userId));
        }
    }
}
