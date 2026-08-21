package com.edumatrix.org;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.org.support.OrgFixtures;
import com.edumatrix.org.support.OrgIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 03-02 §3.5 节点停用 / 启用。
 *
 * <p>三条硬约束：只写 {@code org_node.status} 一行、<b>绝不碰 {@code sys_user.status}</b>；
 * 停用先 SADD 冻结集再提交、启用先提交再 SREM；停用效果按节点类型自动区分。
 */
class NodeStatusIT extends OrgIntegrationTestBase {

    @Test
    @DisplayName("停用只写 org_node.status 一行，sys_user.status 全程不动（否则停用可逆、启用不可逆）")
    void disablingNeverTouchesAccountStatus() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.A2 + "/status",
                token, "{\"status\":1,\"remark\":\"交接期冻结\"}"))).isEqualTo(200);

        assertThat(orgFixtures.nodeStatusOf(OrgFixtures.A2)).isEqualTo(1);
        // 契约 §2.3：sys_user.status 是与组织无关的账号级封禁，仅超管可写，两者不联动
        assertThat(orgFixtures.userStatusOf(OrgFixtures.A2)).isZero();
        assertThat(orgFixtures.userStatusOf(OrgFixtures.TX)).isZero();
        // 不做级联写库：子节点一行都没改
        assertThat(orgFixtures.nodeStatusOf(OrgFixtures.TX)).isZero();
    }

    @Test
    @DisplayName("停用先 SADD 冻结集再提交；启用先提交再 SREM")
    void frozenSetIsWrittenInTheRightOrder() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);
        String frozenKey = RedisKeys.frozenSet(OrgFixtures.TENANT_ID);

        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.A2 + "/status",
                token, "{\"status\":1}"))).isEqualTo(200);
        assertThat(redisTemplate.opsForSet().isMember(frozenKey, String.valueOf(OrgFixtures.A2)))
                .isTrue();

        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.A2 + "/status",
                token, "{\"status\":0}"))).isEqualTo(200);
        assertThat(redisTemplate.opsForSet().isMember(frozenKey, String.valueOf(OrgFixtures.A2)))
                .isFalse();
        assertThat(orgFixtures.nodeStatusOf(OrgFixtures.A2)).isZero();
    }

    @Test
    @DisplayName("停用管理员节点 = 分支冻结：affectedNodeCount 含整棵子树")
    void disablingAdminReportsWholeSubtree() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode data = data(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.A1 + "/status",
                token, "{\"status\":1}"));

        // A1 + T1 + T2 + T3 + 8 名学生 = 12
        //（F-114 改形前是 14：还含 P 与 A3 两个嵌套管理员）
        assertThat(data.path("affectedNodeCount").asInt()).isEqualTo(12);
        // 每个节点都是一个人，所以两个计数恒等
        assertThat(data.path("affectedUserCount").asInt()).isEqualTo(12);
    }

    @Test
    @DisplayName("停用教师节点 = 仅本人：affectedNodeCount 恒为 1，名下学员照常")
    void disablingTeacherAffectsOnlyHimself() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        JsonNode data = data(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.T3 + "/status",
                token, "{\"status\":1}"));

        // 级联会让整批学员突然登不进去 —— 契约 §2.3 称之为业务事故。
        // 把学员算进 affectedNodeCount 就是把一件没发生的事写进响应
        assertThat(data.path("affectedNodeCount").asInt()).isEqualTo(1);
        assertThat(orgFixtures.nodeStatusOf(OrgFixtures.S6)).isZero();
    }

    @Test
    @DisplayName("停用后不可作为移动目标 → 10109；启用后恢复")
    void disabledNodeCannotBeMoveTarget() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.TX + "/status",
                token, "{\"status\":1}"))).isEqualTo(200);
        assertThat(code(move(token, OrgFixtures.S8, OrgFixtures.TX))).isEqualTo(10109);

        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.TX + "/status",
                token, "{\"status\":0}"))).isEqualTo(200);
        assertThat(code(move(token, OrgFixtures.S8, OrgFixtures.TX))).isEqualTo(200);
    }

    @Test
    @DisplayName("不允许停用自己所在节点 → 10012")
    void cannotDisableOwnNode() throws Exception {
        String token = loginAs(OrgFixtures.A2);

        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.A2 + "/status",
                token, "{\"status\":1}"))).isEqualTo(10012);
        assertThat(orgFixtures.nodeStatusOf(OrgFixtures.A2)).isZero();
    }

    @Test
    @DisplayName("停用目标不在我的子树内 → 10107")
    void cannotDisableOutsideMySubtree() throws Exception {
        String token = loginAs(OrgFixtures.A2);

        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.T1 + "/status",
                token, "{\"status\":1}"))).isEqualTo(10107);
    }
}
