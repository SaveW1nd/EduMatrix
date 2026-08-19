package com.edumatrix.system.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.edumatrix.auth.support.AuthFixtures;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 模块 04 集成测试的公共底座。
 *
 * <p>继承 {@link SystemIntegrationTestBase}（进而是 {@code AuthIntegrationTestBase}）
 * 而不是自定义上下文——理由是那两个类注释里同一条：{@code TenantHelper} 的 provider 是
 * <b>静态字段</b>，多一个 Spring 测试上下文就会把它指向别人的 provider。
 * 所以本模块同样<b>不加 {@code @TestConfiguration}、不用 {@code @MockBean}</b>。
 * 判据 2 那条"打断第②步"因此改用数据手段实现，见
 * {@link TenantFixtures#hidePlatformRootNode()}。
 */
public abstract class TenantIntegrationTestBase extends SystemIntegrationTestBase {

    protected static final String TENANTS = "/api/v1/system/tenants";
    protected static final String TENANT_CONFIGS = "/api/v1/system/tenant-configs";

    protected TenantFixtures tenantFixtures;

    @BeforeEach
    void setUpTenantBase() {
        tenantFixtures = new TenantFixtures(jdbcTemplate);
        tenantFixtures.snapshotAndClean();
    }

    @AfterEach
    void tearDownTenantBase() {
        // setUp 失败时本方法【仍会执行】，而那时 tenantFixtures 可能还没被赋值。
        // 不加空判的话，这里抛出的 NPE 会把【真正的失败原因】埋在下面 ——
        // 排查的人第一眼看到的是 NullPointerException，而不是那条 Duplicate entry。
        // 这不是吞异常：setUp 的原始异常照常上报，本判定只是不再往上面加噪声。
        if (tenantFixtures != null) {
            tenantFixtures.restoreAndClean();
        }
    }

    /** 平台超管：{@code system:tenant:*} 七个标识只绑了他。 */
    protected String superAdminToken() throws Exception {
        return client.loginForToken(AuthFixtures.SUPER_ADMIN_USERNAME, AuthFixtures.PASSWORD);
    }

    /**
     * 机构管理员：{@code system:tenantConfig:*} 只绑了他，而 §5 的七个他一个都调不了。
     *
     * <p><b>用 {@code ADMIN_USERNAME}（下级管理员）而不是 {@code ROOT_USERNAME}（机构最高管理员）</b>：
     * 后者在 {@link AuthFixtures} 里 {@code pwd_reset_flag = 1}（"初始密码未改"是它存在的理由），
     * 而 {@code AuthSessionGuard} 对未改密的会话<b>除三条放行接口外一律 403</b>。
     * 拿它当 org_admin 会让本模块的 403 断言<b>因为错误的原因通过</b>——
     * 挡住请求的是强制改密门禁，而不是 {@code @SaCheckPermission}，
     * 于是"权限标识只绑了谁"这件事根本没被验到。两个账号都绑着预置 {@code org_admin}，
     * 换成下级管理员不损失任何角色语义。
     */
    protected String orgAdminToken() throws Exception {
        return client.loginForToken(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
    }

    /** 开通一个机构（§5.3）。名称与用户名带上夹具前缀，才会被清理捡到。 */
    protected JsonNode createTenant(String nameSuffix, String usernameSuffix) throws Exception {
        return createTenant(nameSuffix, usernameSuffix, "2099-12-31 23:59:59", 2000);
    }

    protected JsonNode createTenant(String nameSuffix, String usernameSuffix,
                                    String expireTime, int maxStudentCount) throws Exception {
        return client.postWithToken(TENANTS, superAdminToken(), """
                {"name":"%s","contactName":"陈静","contactPhone":"13900139002",
                 "expireTime":"%s","maxStudentCount":%d,
                 "adminUsername":"%s","adminRealName":"陈静","remark":"IT 开通"}
                """.formatted(tenantName(nameSuffix), expireTime, maxStudentCount,
                adminUsername(usernameSuffix)));
    }

    protected static String tenantName(String suffix) {
        return TenantFixtures.TENANT_NAME_PREFIX + suffix;
    }

    protected static String adminUsername(String suffix) {
        return TenantFixtures.ADMIN_USERNAME_PREFIX + suffix;
    }

    protected static long dataLong(JsonNode response, String field) {
        return response.path("data").path(field).asLong();
    }

    protected static String dataText(JsonNode response, String field) {
        return response.path("data").path(field).asText();
    }
}
