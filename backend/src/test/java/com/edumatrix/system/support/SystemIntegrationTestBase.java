package com.edumatrix.system.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.edumatrix.auth.support.AuthIntegrationTestBase;

/**
 * 模块 03 集成测试的公共底座。
 *
 * <h2>为什么继承模块 02 的底座，而不是自定义一个上下文</h2>
 * <p>{@code AuthIntegrationTestBase} 的类注释把理由写死了：{@code TenantHelper} 的 provider
 * 是<b>静态字段</b>（它要被无 Spring 上下文的 MyBatis 拦截器内部直接调用），
 * 而 Spring 的测试上下文按配置缓存 —— <b>每建一个新上下文就会重新执行
 * {@code TenantConfig.tenantHelperInitializer}</b>，把那个静态字段指向新上下文的 provider。
 * 于是"第二个上下文"一旦出现，先前上下文里的测试类再跑就会读到别人的 provider。
 *
 * <p>所以本模块的 IT <b>一律沿用同一个上下文配置</b>：不加 {@code @TestConfiguration}、
 * 不用 {@code @MockBean}，任何会改变上下文缓存键的东西都不用。继承那个底座是
 * 唯一能保证这一点的方式，也顺带拿到了它的组织树夹具与真实登录客户端。
 */
public abstract class SystemIntegrationTestBase extends AuthIntegrationTestBase {

    protected SystemFixtures systemFixtures;

    @BeforeEach
    void setUpSystemBase() {
        systemFixtures = new SystemFixtures(jdbcTemplate);
        systemFixtures.seed();
    }

    @AfterEach
    void tearDownSystemBase() {
        systemFixtures.clean();
    }

    /** DELETE 也要带 Token —— {@code AuthTestClient} 只有 get/post/put，这里补一个。 */
    protected com.fasterxml.jackson.databind.JsonNode deleteWithToken(String path, String token)
            throws Exception {
        var result = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                        .header("Authorization", "Bearer " + token)).andReturn();
        String content = result.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(content.isBlank() ? "{}" : content);
    }

    /** 取响应体的 {@code code}。 */
    protected static int code(com.fasterxml.jackson.databind.JsonNode response) {
        return response.path("code").asInt();
    }
}
