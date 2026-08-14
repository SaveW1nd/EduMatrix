package com.edumatrix.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.common.tenantconfig.TenantConfigHelper;
import com.edumatrix.common.tenantconfig.TenantConfigKey;
import com.edumatrix.system.support.TenantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判据 5：租户配置的白名单、值域与回落（03-01 §6.1 / §6.2，契约 §5 末）。
 *
 * <p>用 {@link AuthFixtures} 那个测试租户的机构管理员调——§6 两个接口<b>只对
 * {@code org_admin} 开放</b>，超管反而 403（他没有租户上下文）。
 */
class TenantConfigIT extends TenantIntegrationTestBase {

    private static final String COMPLETE_RATE = "complete_rate_threshold";
    private static final String WATERMARK = "watermark_phone_mask";

    /** 跨领域 SPI：模块 12/13 拿到的就是这个接口（实现在 {@code system/tenant}）。 */
    @Autowired
    private TenantConfigHelper tenantConfigHelper;

    // =====================================================================
    // §6.2 写侧
    // =====================================================================

    @Test
    @DisplayName("判据 5｜白名单外的 key → 10016")
    void unknownConfigKeyIsRejected() throws Exception {
        JsonNode response = client.putWithToken(
                TENANT_CONFIGS + "/aliyun_access_key_id", orgAdminToken(),
                """
                {"configValue":"LTAI5t"}
                """);

        // 白名单是穷举（契约 §5 末）。尤其【不得】把云厂商/AK/SK 塞进来 ——
        // 那是平台部署级参数，走环境变量不入库（§E 的 F-5）
        assertThat(code(response)).isEqualTo(10016);
    }

    @Test
    @DisplayName("判据 5｜complete_rate_threshold 传 50 → 400（合法范围 60~100）")
    void outOfRangeValueIsRejected() throws Exception {
        JsonNode response = putConfig(COMPLETE_RATE, "50");

        assertThat(code(response)).isEqualTo(400);
        // msg 要提示该键的合法范围（§6.2 括注），否则前端只能显示一句"参数校验失败"
        assertThat(response.path("msg").asText()).contains("60~100");
        assertThat(tenantFixtures.configValue(AuthFixtures.TENANT_ID, COMPLETE_RATE)).isNull();
    }

    @Test
    @DisplayName("判据 5｜complete_rate_threshold 传 90 → 成功，落库并可回读")
    void inRangeValueIsAccepted() throws Exception {
        JsonNode response = putConfig(COMPLETE_RATE, "90");

        assertThat(code(response)).isEqualTo(200);
        assertThat(dataText(response, "configValue")).isEqualTo("90");
        assertThat(response.path("data").path("isDefault").asBoolean()).isFalse();
        assertThat(tenantFixtures.configValue(AuthFixtures.TENANT_ID, COMPLETE_RATE)).isEqualTo("90");
    }

    @Test
    @DisplayName("§6.2｜类型不符（非整数）→ 400，不做隐式转换")
    void nonIntegerValueIsRejected() throws Exception {
        assertThat(code(putConfig(COMPLETE_RATE, "85.0"))).isEqualTo(400);
        assertThat(code(putConfig(WATERMARK, "true"))).isEqualTo(400);
    }

    @Test
    @DisplayName("§6.2｜PUT 幂等：重复写同一个键命中 UK 走更新，不产生第二行")
    void repeatedWriteUpdatesTheSameRow() throws Exception {
        assertThat(code(putConfig(COMPLETE_RATE, "80"))).isEqualTo(200);
        assertThat(code(putConfig(COMPLETE_RATE, "95"))).isEqualTo(200);

        assertThat(tenantFixtures.configValue(AuthFixtures.TENANT_ID, COMPLETE_RATE)).isEqualTo("95");
        JsonNode list = client.getWithToken(TENANT_CONFIGS, orgAdminToken());
        assertThat(list.path("data")).hasSize(2);
    }

    // =====================================================================
    // §6.1 读侧
    // =====================================================================

    @Test
    @DisplayName("§6.1｜固定返回白名单全部两个键，未自定义的 isDefault=true、updateTime=null")
    void listReturnsWholeWhitelistIncludingUntouchedKeys() throws Exception {
        JsonNode response = client.getWithToken(TENANT_CONFIGS, orgAdminToken());

        assertThat(code(response)).isEqualTo(200);
        JsonNode list = response.path("data");
        // 白名单是穷举、只有两个（契约 §5 末）。多出第三个就是有人私自扩了键
        assertThat(list).hasSize(2);
        // 按 configKey 升序
        assertThat(list.get(0).path("configKey").asText()).isEqualTo(COMPLETE_RATE);
        assertThat(list.get(1).path("configKey").asText()).isEqualTo(WATERMARK);

        for (JsonNode item : list) {
            assertThat(item.path("isDefault").asBoolean()).isTrue();
            assertThat(item.path("updateTime").isNull()).isTrue();
            assertThat(item.path("configValue").asText())
                    .isEqualTo(item.path("defaultValue").asText());
        }
        assertThat(list.get(0).path("defaultValue").asText()).isEqualTo("90");
        // 【默认 1，不是 0】：契约 §5 与 §7.2 —— 默认 0 且对学生端生效的话，
        // 一个租户什么都不配就已经违反个保法第 31 条，而对象是 K12 未成年人的手机号。
        // 03-01 §6 导语表格的「默认值」列与 §6.1 示例仍写 0（与同格描述文字自相矛盾），
        // 按权威顺序取契约；分册待订正，已登记 F-24
        assertThat(list.get(1).path("defaultValue").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("§6.1｜写过的键 isDefault=false 且带 updateTime，未写过的仍回落默认值")
    void listMixesSavedAndDefaultValues() throws Exception {
        assertThat(code(putConfig(COMPLETE_RATE, "85"))).isEqualTo(200);

        JsonNode list = client.getWithToken(TENANT_CONFIGS, orgAdminToken()).path("data");

        assertThat(list.get(0).path("configValue").asText()).isEqualTo("85");
        assertThat(list.get(0).path("isDefault").asBoolean()).isFalse();
        assertThat(list.get(0).path("updateTime").isNull()).isFalse();
        assertThat(list.get(1).path("configValue").asText()).isEqualTo("1");
        assertThat(list.get(1).path("isDefault").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("§6.1｜super_admin 调 → 403（平台级无租户上下文）")
    void superAdminCannotReadTenantConfigs() throws Exception {
        assertThat(code(client.getWithToken(TENANT_CONFIGS, superAdminToken()))).isEqualTo(403);
        assertThat(code(client.putWithToken(TENANT_CONFIGS + "/" + COMPLETE_RATE,
                superAdminToken(), """
                        {"configValue":"85"}
                        """))).isEqualTo(403);
    }

    // =====================================================================
    // 判据 5｜TenantConfigHelper 的回落
    // =====================================================================

    @Test
    @DisplayName("判据 5｜TenantConfigHelper 读不到时回落默认值 90；写入后读到写入值")
    void helperFallsBackToPlatformDefault() throws Exception {
        // 未写入过：回落平台默认值。模块 13 判完播走的就是这一条
        int fallback = TenantHelper.runWithTenant(AuthFixtures.TENANT_ID,
                () -> tenantConfigHelper.getInt(TenantConfigKey.COMPLETE_RATE_THRESHOLD));
        assertThat(fallback).isEqualTo(90);

        // 水印开关的默认值是 1（脱敏），模块 12 取的就是它
        int watermark = TenantHelper.runWithTenant(AuthFixtures.TENANT_ID,
                () -> tenantConfigHelper.getInt(TenantConfigKey.WATERMARK_PHONE_MASK));
        assertThat(watermark).isEqualTo(1);

        assertThat(code(putConfig(COMPLETE_RATE, "75"))).isEqualTo(200);

        int saved = TenantHelper.runWithTenant(AuthFixtures.TENANT_ID,
                () -> tenantConfigHelper.getInt(TenantConfigKey.COMPLETE_RATE_THRESHOLD));
        assertThat(saved).isEqualTo(75);
    }

    @Test
    @DisplayName("判据 5｜别的租户写过不影响本租户：helper 按租户隔离，不串行")
    void helperIsIsolatedPerTenant() throws Exception {
        assertThat(code(putConfig(COMPLETE_RATE, "70"))).isEqualTo(200);

        // 租户 B（到期租户）从没写过这个键 —— 必须回落 90，而不是读到租户 A 的 70。
        // 少写 tenant_id 条件时这里会读成 70：超管/无会话上下文下插件不注入租户条件
        int otherTenant = TenantHelper.runWithTenant(AuthFixtures.EXPIRED_TENANT_ID,
                () -> tenantConfigHelper.getInt(TenantConfigKey.COMPLETE_RATE_THRESHOLD));
        assertThat(otherTenant).isEqualTo(90);
    }

    @Test
    @DisplayName("判据 5｜库里是越界/非整数的历史脏数据时，helper 回落默认值而不是抛异常")
    void helperFallsBackOnCorruptedValue() {
        tenantFixtures.insertConfig(AuthFixtures.TENANT_ID, COMPLETE_RATE, "abc");
        assertThat(TenantHelper.runWithTenant(AuthFixtures.TENANT_ID,
                () -> tenantConfigHelper.getInt(TenantConfigKey.COMPLETE_RATE_THRESHOLD)))
                .isEqualTo(90);

        tenantFixtures.insertConfig(AuthFixtures.TENANT_ID, COMPLETE_RATE, "5");
        assertThat(TenantHelper.runWithTenant(AuthFixtures.TENANT_ID,
                () -> tenantConfigHelper.getInt(TenantConfigKey.COMPLETE_RATE_THRESHOLD)))
                .isEqualTo(90);
    }

    // =====================================================================

    private JsonNode putConfig(String configKey, String configValue) throws Exception {
        return client.putWithToken(TENANT_CONFIGS + "/" + configKey, orgAdminToken(), """
                {"configValue":"%s"}
                """.formatted(configValue));
    }
}
