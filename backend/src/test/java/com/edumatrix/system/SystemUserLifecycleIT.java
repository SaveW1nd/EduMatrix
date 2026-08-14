package com.edumatrix.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.system.support.SystemIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §2.2 建号 / §2.4 删号 / §2.5 重置密码的验收（判据 9 / 10 + §2.2 副作用 + {@code 10012}）。
 *
 * <p>全部以 {@code super_admin} 身份调用 —— 本组写接口一律仅超管（§2 导语），
 * org_admin 被拒的那一半在 {@code SystemUserWriteScopeIT}。
 */
class SystemUserLifecycleIT extends SystemIntegrationTestBase {

    private static final String USERS = "/api/v1/system/users";
    private static final String NEW_TEACHER = "it_new_teacher";

    // =====================================================================
    // §2.2 副作用：org_node + org_node_change_log 同事务落地
    // =====================================================================

    @Test
    @DisplayName("§2.2 副作用｜同事务落 org_node 与 org_node_change_log(change_type=1)，nodeType == userType")
    void createUserAlsoCreatesNodeAndChangeLog() throws Exception {
        Integer parentChildCountBefore = systemFixtures.nodeChildCount(AuthFixtures.ADMIN_NODE);

        JsonNode response = createTeacherUnder(AuthFixtures.ADMIN_NODE);

        assertThat(code(response)).isEqualTo(200);
        long userId = response.path("data").path("id").asLong();
        long nodeId = response.path("data").path("nodeId").asLong();

        // ① sys_user.node_id 已回写
        assertThat(systemFixtures.userNodeId(userId)).isEqualTo(nodeId);
        // ② org_node.ref_user_id 反向指回来（契约 §2.3：两者互为反向引用，1:1）
        assertThat(systemFixtures.nodeRefUserId(nodeId)).isEqualTo(userId);
        // ③ node_type 与 userType【恒等，不做任何映射】（契约 §5 / §2.2 参数表那段警告）。
        //    映射错了不会报错，只会让这个教师永远分配不到学员（10106）、
        //    stat_*.teacher_node_id 恒为 NULL、导师看板恒空
        assertThat(systemFixtures.nodeType(nodeId)).isEqualTo(2);
        assertThat(response.path("data").path("nodeType").asInt())
                .isEqualTo(response.path("data").path("userType").asInt());
        // ④ ancestors 由父节点推导（父的 selfPrefix）
        assertThat(systemFixtures.nodeAncestors(nodeId))
                .isEqualTo("0," + AuthFixtures.TENANT_ID + "," + AuthFixtures.ADMIN_NODE);
        // ⑤ 建档轨迹恰好一条，from_parent_id 为 NULL、to_parent_id 为父节点（DDL 注释）
        assertThat(systemFixtures.createChangeLogCount(nodeId)).isEqualTo(1);
        assertThat(systemFixtures.changeLogFromParentId(nodeId)).isNull();
        assertThat(systemFixtures.changeLogToParentId(nodeId)).isEqualTo(AuthFixtures.ADMIN_NODE);
        // ⑥ 父节点 child_count + 1
        assertThat(systemFixtures.nodeChildCount(AuthFixtures.ADMIN_NODE))
                .isEqualTo(parentChildCountBefore + 1);
        // ⑦ 面包屑自机构根起，不含平台根哨兵（契约 §2.9）
        assertThat(response.path("data").path("nodePath").asText())
                .isEqualTo("IT 测试机构/校区管理员/新教师");
    }

    @Test
    @DisplayName("§2.2｜教师节点下建教师 → 10105（教师节点下只能挂学生）")
    void teacherNodeOnlyAcceptsStudent() throws Exception {
        JsonNode response = createTeacherUnder(AuthFixtures.TEACHER_NODE);

        assertThat(code(response)).isEqualTo(10105);
        assertThat(systemFixtures.userRowCount(NEW_TEACHER)).isZero();
    }

    @Test
    @DisplayName("§2.2｜学生节点下建任何节点 → 10106（学生是叶子）")
    void studentNodeMustBeLeaf() throws Exception {
        JsonNode response = createTeacherUnder(AuthFixtures.STUDENT1_NODE);

        assertThat(code(response)).isEqualTo(10106);
    }

    @Test
    @DisplayName("§2.2｜整条事务原子：类型校验失败时 sys_user 一行都不留")
    void failedNodeCreationRollsBackTheUserRow() throws Exception {
        JsonNode response = createTeacherUnder(AuthFixtures.STUDENT1_NODE);

        assertThat(code(response)).isEqualTo(10106);
        // sys_user 先插、org_node 后插，节点校验失败必须把账号一并回滚 ——
        // 否则留下一个「有账号无节点」的孤儿，正是 PRD F1-3 规则 1 禁止的形态
        assertThat(systemFixtures.userRowCount(NEW_TEACHER)).isZero();
    }

    // =====================================================================
    // 判据 9｜同名重建
    // =====================================================================

    @Test
    @DisplayName("判据 9｜创建 U → 逻辑删除 → 再创建同名 U 成功（uk_username 末尾的 deleted_at 放行）")
    void sameUsernameCanBeRecreatedAfterLogicalDelete() throws Exception {
        String token = superAdminToken();

        JsonNode first = createTeacherUnder(AuthFixtures.ADMIN_NODE);
        assertThat(code(first)).isEqualTo(200);
        long firstUserId = first.path("data").path("id").asLong();

        assertThat(code(deleteWithToken(USERS + "/" + firstUserId, token))).isEqualTo(200);

        JsonNode second = createTeacherUnder(AuthFixtures.ADMIN_NODE);

        // 【不要自己判"这个用户名是不是被删过"】—— uk_username(username, deleted_at) 已经处理了。
        // 这是 deleted_at 用毫秒时间戳而非 0/1 的直接收益：0/1 方案下同一用户名
        // 最多容纳一条已删除行，第二次删除同名用户就撞唯一键
        assertThat(code(second)).isEqualTo(200);
        assertThat(second.path("data").path("id").asLong()).isNotEqualTo(firstUserId);
        // 库里两行同名：一行已删（deleted_at != 0）、一行在用
        assertThat(systemFixtures.userRowCount(NEW_TEACHER)).isEqualTo(2);
    }

    @Test
    @DisplayName("§2.2｜用户名与在用账号冲突 → 10001")
    void duplicateUsernameIsRejected() throws Exception {
        assertThat(code(createTeacherUnder(AuthFixtures.ADMIN_NODE))).isEqualTo(200);

        JsonNode again = createTeacherUnder(AuthFixtures.ADMIN_NODE);

        assertThat(code(again)).isEqualTo(10001);
    }

    // =====================================================================
    // 判据 10｜§2.4 子节点保护
    // =====================================================================

    @Test
    @DisplayName("判据 10｜删除一个仍有未删除子节点的用户 → 10108，账号与节点都还在")
    void deletingUserWithLiveChildNodeIsRejected() throws Exception {
        String token = superAdminToken();
        // ADMIN 节点下挂着 S1，是现成的"有子节点"样本
        JsonNode response = deleteWithToken(USERS + "/" + AuthFixtures.ADMIN_USER, token);

        assertThat(code(response)).isEqualTo(10108);
        // 整个事务回滚：账号与节点都必须原样在
        assertThat(systemFixtures.userIdByUsername(AuthFixtures.ADMIN_USERNAME)).isNotNull();
        assertThat(systemFixtures.nodeDeletedAt(AuthFixtures.ADMIN_NODE)).isZero();
    }

    @Test
    @DisplayName("§2.4｜删除叶子账号 → 账号与节点同时逻辑删除、父节点 child_count -1、不写异动轨迹")
    void deletingLeafUserRemovesNodeAndDecrementsChildCount() throws Exception {
        String token = superAdminToken();
        JsonNode created = createTeacherUnder(AuthFixtures.ADMIN_NODE);
        long userId = created.path("data").path("id").asLong();
        long nodeId = created.path("data").path("nodeId").asLong();
        Integer childCountAfterCreate = systemFixtures.nodeChildCount(AuthFixtures.ADMIN_NODE);

        assertThat(code(deleteWithToken(USERS + "/" + userId, token))).isEqualTo(200);

        assertThat(systemFixtures.userIdByUsername(NEW_TEACHER)).isNull();
        assertThat(systemFixtures.nodeDeletedAt(nodeId)).isNotZero();
        assertThat(systemFixtures.nodeChildCount(AuthFixtures.ADMIN_NODE))
                .isEqualTo(childCountAfterCreate - 1);
        // §2.4 的原文只有「逻辑删除节点 + 作废 Token」两件事，【不写异动轨迹】。
        // 03-02 的三个删除接口同样不写。补一条是发明规则
        assertThat(systemFixtures.changeLogCount(nodeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("§2.4｜对已删除用户重复调用同样返回 200（幂等）")
    void deleteIsIdempotent() throws Exception {
        String token = superAdminToken();
        JsonNode created = createTeacherUnder(AuthFixtures.ADMIN_NODE);
        long userId = created.path("data").path("id").asLong();

        assertThat(code(deleteWithToken(USERS + "/" + userId, token))).isEqualTo(200);
        assertThat(code(deleteWithToken(USERS + "/" + userId, token))).isEqualTo(200);
    }

    @Test
    @DisplayName("§2.4｜删除后该账号在线 Token 立即作废")
    void deleteRevokesVictimSessions() throws Exception {
        String token = superAdminToken();
        JsonNode created = createTeacherUnder(AuthFixtures.ADMIN_NODE);
        long userId = created.path("data").path("id").asLong();

        String victimToken = client.loginForToken(NEW_TEACHER, "Abcd1234");
        assertThat(code(client.getWithToken("/api/v1/auth/me", victimToken))).isEqualTo(200);

        assertThat(code(deleteWithToken(USERS + "/" + userId, token))).isEqualTo(200);

        // 跨领域能力走 common/account 的 SessionRevoker SPI（实现在 auth）——
        // 检查③禁止 system 直接 import TokenService
        assertThat(code(client.getWithToken("/api/v1/auth/me", victimToken))).isEqualTo(401);
    }

    // =====================================================================
    // 10012｜不允许对当前登录账号执行该操作
    // =====================================================================

    @Test
    @DisplayName("10012｜超管删除自己 → 10012")
    void cannotDeleteSelf() throws Exception {
        JsonNode response = deleteWithToken(USERS + "/" + AuthFixtures.SUPER_ADMIN_USER,
                superAdminToken());

        assertThat(code(response)).isEqualTo(10012);
        assertThat(systemFixtures.userIdByUsername(AuthFixtures.SUPER_ADMIN_USERNAME)).isNotNull();
    }

    @Test
    @DisplayName("10012｜超管停用自己 → 10012")
    void cannotDisableSelf() throws Exception {
        JsonNode response = client.putWithToken(
                USERS + "/" + AuthFixtures.SUPER_ADMIN_USER + "/status", superAdminToken(),
                """
                {"status":1}
                """);

        assertThat(code(response)).isEqualTo(10012);
        assertThat(systemFixtures.userStatus(AuthFixtures.SUPER_ADMIN_USER)).isZero();
    }

    @Test
    @DisplayName("10012｜超管移除自己的 super_admin 角色（降权）→ 10012")
    void cannotDemoteSelf() throws Exception {
        JsonNode response = client.putWithToken(USERS + "/" + AuthFixtures.SUPER_ADMIN_USER,
                superAdminToken(),
                """
                {"realName":"平台超管","roleIds":["%d"]}
                """.formatted(AuthFixtures.ROLE_ORG_ADMIN));

        // §2.3 错误码表逐字：「10012 不允许对当前登录账号执行该操作（如移除自己的管理员角色）」。
        // 判据是"新集合不再包含我当前持有的某个角色"，不是"集合更小"——
        // 把 super_admin 换成 org_admin 集合大小不变，但权限没了
        assertThat(code(response)).isEqualTo(10012);
    }

    // =====================================================================
    // §2.5 重置密码
    // =====================================================================

    @Test
    @DisplayName("§2.5｜传了 newPassword → data 为 null（调用方自己知道设的是什么）")
    void resetWithExplicitPasswordReturnsNullData() throws Exception {
        JsonNode response = client.putWithToken(
                USERS + "/" + AuthFixtures.STUDENT2_USER + "/password/reset", superAdminToken(),
                """
                {"newPassword":"Stu@202608"}
                """);

        assertThat(code(response)).isEqualTo(200);
        assertThat(response.path("data").isNull()).isTrue();
        // 新口令确实可登录，且 pwd_reset_flag 置 1（PRD F1-3 规则 3：强制首次登录改密）
        assertThat(systemFixtures.userPwdResetFlag(AuthFixtures.STUDENT2_USER)).isEqualTo(1);
        assertThat(code(client.login(AuthFixtures.STUDENT2_USERNAME, "Stu@202608"))).isEqualTo(200);
    }

    @Test
    @DisplayName("§2.5｜不传 newPassword → 服务端生成 ≥12 位强口令，明文仅返回一次")
    void resetWithoutPasswordGeneratesStrongOne() throws Exception {
        JsonNode response = client.putWithToken(
                USERS + "/" + AuthFixtures.STUDENT2_USER + "/password/reset", superAdminToken(),
                "{}");

        assertThat(code(response)).isEqualTo(200);
        String generated = response.path("data").path("initialPassword").asText();
        // §2.5 参数表：≥12 位，含大小写字母 + 数字 + 符号。
        // 【禁止任何固定默认密码】—— 固定常量会出现在文档与工单中，
        // 攻击者拿到用户名列表即可批量撞库命中所有"已重置未改密"的账号
        assertThat(generated).hasSizeGreaterThanOrEqualTo(12);
        assertThat(generated).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*\\d.*");
        assertThat(generated).doesNotContain(AuthFixtures.STUDENT2_USERNAME);
        assertThat(code(client.login(AuthFixtures.STUDENT2_USERNAME, generated))).isEqualTo(200);
    }

    @Test
    @DisplayName("§2.5｜重置后该用户全部在线会话强制下线")
    void resetRevokesAllSessions() throws Exception {
        String victimToken = client.loginForToken(
                AuthFixtures.STUDENT2_USERNAME, AuthFixtures.PASSWORD);
        assertThat(code(client.getWithToken("/api/v1/auth/me", victimToken))).isEqualTo(200);

        JsonNode response = client.putWithToken(
                USERS + "/" + AuthFixtures.STUDENT2_USER + "/password/reset", superAdminToken(),
                """
                {"newPassword":"Stu@202608"}
                """);

        assertThat(code(response)).isEqualTo(200);
        assertThat(code(client.getWithToken("/api/v1/auth/me", victimToken))).isEqualTo(401);
    }

    // =====================================================================

    private JsonNode createTeacherUnder(long parentNodeId) throws Exception {
        return client.postWithToken(USERS, superAdminToken(),
                """
                {"username":"%s","password":"Abcd1234","realName":"新教师","userType":2,
                 "parentNodeId":"%d","roleIds":["%d"]}
                """.formatted(NEW_TEACHER, parentNodeId, AuthFixtures.ROLE_TEACHER));
    }

    private String superAdminToken() throws Exception {
        return client.loginForToken(AuthFixtures.SUPER_ADMIN_USERNAME, AuthFixtures.PASSWORD);
    }
}
