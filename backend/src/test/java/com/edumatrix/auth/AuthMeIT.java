package com.edumatrix.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /auth/me} 的验收（判据 5）。
 *
 * <h2>四角色 perms 计数：以<b>迁移脚本落库后的实测值</b>为准</h2>
 * <pre>
 * org_admin 94 ｜ teacher 61 ｜ super_admin 31 ｜ student 0（基线值；逐轮迁移后的现值见下面的常量注释）
 * </pre>
 * <p>04-实施计划.md §B 模块 02 规则 11 原先写的是 {@code 93 / 61 / 31 / 2}，
 * 那是<b>同一份文档内部的陈旧复述</b>，两处差异都能在 §E 里找到解释：
 * <ul>
 *   <li><b>student 0</b> —— F-1 定案②：「{@code student} 不绑任何菜单行……
 *       {@code sys_role_menu} 中 {@code student} 的 2 条绑定已删除（201 → 200 行）」，
 *       库里正好 200 行；
 *   <li><b>org_admin 94</b> —— F-2 定案：「菜单 A14 的 {@code visible} 由 0 改 1
 *       <b>并绑 {@code org_admin}</b>」，93 + 1 = 94。
 * </ul>
 * 规则 11 的数字已按实测值订正（本模块唯一一处文档改动）。
 *
 * <h2>空数组有两种成因，必须能分辨</h2>
 * <p>{@code perms: []} 既可能是 F-1 定案②的<b>设计意图</b>（学生），
 * 也可能是<b>租户插件的平台级放行失效</b>（契约 §2.9 那个「接口 200、字段齐全、
 * 数组为空」的不报错故障）。两者的外部表现完全一样。
 * 区分方法写死在下面的用例里：<b>看 {@code roles}</b> —— 里面有那一行，
 * 就说明 {@code tenant_id = 0} 的放行是通的。
 */
class AuthMeIT extends AuthIntegrationTestBase {

    /**
     * org_admin 的 perms：<b>94 → 96</b>（模块 07 的迁移
     * {@code V202608160000__split_staff_list_perms.sql} 把 {@code org:staff:list}
     * 拆出 {@code org:admin:list} 与 {@code org:teacher:list}，两者都绑 org_admin）。
     *
     * <p><b>96 → 95</b>：{@code V202608210100} 删掉孤儿权限 {@code org:grant:edit}
     *（需方 2026-08-21 定案「没人用的权限 直接删掉」，F-104 —— 它的端点接口 40
     * 已随取消授权有效期删除）。<b>org_admin 与 teacher 两边同时少一个</b>，
     * 那条菜单绑给了两个角色。
     */
    private static final int PERMS_ORG_ADMIN = 95;
    /**
     * teacher 的 perms：<b>61 → 62</b>（模块 06 的迁移
     * {@code V202608150000__bind_teacher_org_node_list.sql} 补了 {@code org:node:list}）。
     *
     * <p>基线 {@code V202608140000} 只把该菜单绑给了 {@code super_admin} 与 {@code org_admin}，
     * 而 03-02 §3.1/§3.2 的权限栏都写着 {@code teacher}，§3.1 数据权限还专门写
     * 「教师调用时树根即其教师节点，返回的子树就是名下学员列表」——照基线实现，
     * 教师调组织树查询与节点详情会拿 403。
     *
     * <p><b>62 → 63</b>：模块 07 的 {@code V202608160000} 又补了 {@code org:teacher:list}
     * （拆分前教师连自己那一行都列不出来，F-30）。<b>{@code org:admin:list} 没有绑给它</b> ——
     * 拆分的全部意义就在这一个差别上。
     *
     * <p><b>63 → 60</b>：模块 10 的 {@code V202608200000} 撤销了
     * {@code question:category:add / edit / remove} 三条（F-72）——
     * 03-04 §1.2 与 PRD F3-1 规则 8 都写「分类树的增删改<b>仅 org_admin</b>」，
     * 而契约 §10 附表 A 与初始化脚本此前<b>一致地</b>把它们绑给了 teacher，
     * 于是那道门从来没关上过。教师<b>仍能读</b>分类树（随
     * {@code question:question:list} 放行），少的只是三个写按钮。
     *
     * <p><b>60 → 59</b>：{@code V202608210000} 撤销了 {@code vod:video:add}
     *（需方 2026-08-21 定案二「所有的资产归超级管理员所有，别人无权上传」）。
     * 教师<b>仍能</b>看媒资列表、删媒资、重转、禁用启用 —— 定案只说了上传，
     * 其余四个 {@code vod:video:*} 一个都没动。
     *
     * <p><b>59 → 58</b>：{@code V202608210100} 删掉孤儿权限 {@code org:grant:edit}（F-104）。
     * <b>教师原本也能改授权有效期</b> —— 那条菜单绑了 org_admin 与 teacher 两个角色，
     * 所以两边各少一个，不是只有管理员那边动。
     *
     * <p><b>58 → 37</b>：{@code V202608210200} 撤销<b>三类受管资源的全部写权限</b>
     *（需方 2026-08-21 定案，排期 A：资源由管理员生产，教师只教学与管理）——
     * 课程 4 + 章节 4 + 课时 3 + 图文 3 + 题目 4 + 媒资 3 = <b>21 条</b>。
     * 它是 {@code V202608210000} 那一条（只撤上传）的完整版，做法逐字相同。
     *
     * <p><b>撤的只有写，读一条没动</b>：{@code course:course:list} /
     * {@code course:lesson:list} / {@code course:material:list} /
     * {@code question:question:list} / {@code vod:video:list} 全部保留 ——
     * 教师仍要看得见课程、题目、媒资，才能选来授权给名下学员、才能组卷布置作业。
     * {@code homework:*} 六条与 {@code org:grant:grant} / {@code org:grant:revoke}
     * 同样一条没动（需方明确「作业没关系」；授权是教学动作不是内容生产）。
     */
    private static final int PERMS_TEACHER = 37;
    /** super_admin：<b>31 → 33</b>，拆出的两个 perms 都绑了它。 */
    private static final int PERMS_SUPER_ADMIN = 33;
    private static final int PERMS_STUDENT = 0;

    @Test
    @DisplayName("判据 5｜org_admin：roles 非空、perms 95")
    void orgAdminPerms() throws Exception {
        JsonNode me = me(AuthFixtures.ADMIN_USERNAME);

        assertThat(me.path("data").path("roles")).hasSize(1);
        assertThat(me.path("data").path("roles").get(0).path("roleKey").asText()).isEqualTo("org_admin");
        assertThat(me.path("data").path("perms")).hasSize(PERMS_ORG_ADMIN);
    }

    @Test
    @DisplayName("判据 5｜teacher：roles 非空、perms 37（再撤三类受管资源的 21 条写权限，排期 A）")
    void teacherPerms() throws Exception {
        JsonNode me = me(AuthFixtures.TEACHER_USERNAME);

        assertThat(me.path("data").path("roles").get(0).path("roleKey").asText()).isEqualTo("teacher");
        assertThat(me.path("data").path("perms")).hasSize(PERMS_TEACHER);
    }

    @Test
    @DisplayName("判据 5｜super_admin：perms 31；tenant / nodeType 为 null，nodePath 为空串")
    void superAdminPermsAndEmptyOrgFields() throws Exception {
        JsonNode data = me(AuthFixtures.SUPER_ADMIN_USERNAME).path("data");

        assertThat(data.path("roles").get(0).path("roleKey").asText()).isEqualTo("super_admin");
        assertThat(data.path("perms")).hasSize(PERMS_SUPER_ADMIN);

        assertThat(data.path("nodeId").asText())
                .as("§1.5 字段说明：平台超管的 nodeId 为树根标识 \"0\"")
                .isEqualTo("0");
        assertThat(data.path("nodeType").isNull())
                .as("§1.5 字段说明：平台超管的 nodeType 为 null")
                .isTrue();
        assertThat(data.path("tenant").isNull())
                .as("§1.5 字段说明：平台超管的 tenant 为 null")
                .isTrue();
        assertThat(data.path("nodePath").asText())
                .as("面包屑的口径是「自机构根节点起」，超管不属于任何机构 —— "
                        + "空串而不是 null，前端可直接渲染成「无」")
                .isEmpty();
        assertThat(data.path("nodePathIds")).isEmpty();
    }

    @Test
    @DisplayName("判据 5｜student：roles 非空但 perms 为空 —— 这是 F-1 定案②，不是插件失效")
    void studentHasRolesButNoPerms() throws Exception {
        JsonNode data = me(AuthFixtures.STUDENT1_USERNAME).path("data");

        assertThat(data.path("roles"))
                .as("读得到 tenant_id = 0 的内置角色行，说明平台级放行生效 —— "
                        + "若这里也是空的，那就是契约 §2.9 那个「全员零权限」故障，"
                        + "去看 PlatformRowTenantLineInnerInterceptor，不要在 auth 里 workaround")
                .hasSize(1);
        assertThat(data.path("roles").get(0).path("roleKey").asText()).isEqualTo("student");
        assertThat(data.path("perms"))
                .as("F-1 定案②：student 不绑任何菜单行，学生端接口一律不加 @SaCheckPermission")
                .hasSize(PERMS_STUDENT);
    }

    @Test
    @DisplayName("nodePath 自机构根节点起、不含虚拟根 0，且与 nodePathIds 同序")
    void nodePathStartsFromTenantRoot() throws Exception {
        JsonNode data = me(AuthFixtures.STUDENT2_USERNAME).path("data");

        assertThat(data.path("nodePath").asText())
                .as("§1.5：自机构根节点起、以 / 拼接至本节点")
                .isEqualTo("IT 测试机构/教师/学生二");

        JsonNode ids = data.path("nodePathIds");
        assertThat(ids).hasSize(3);
        assertThat(ids.get(0).asText()).isEqualTo(String.valueOf(AuthFixtures.ROOT_NODE));
        assertThat(ids.get(1).asText()).isEqualTo(String.valueOf(AuthFixtures.TEACHER_NODE));
        assertThat(ids.get(2).asText()).isEqualTo(String.valueOf(AuthFixtures.STUDENT2_NODE));
        assertThat(data.path("nodeId").asText())
                .as("00-通用约定 §5：所有 bigint ID 序列化为字符串")
                .isEqualTo(String.valueOf(AuthFixtures.STUDENT2_NODE));
    }

    @Test
    @DisplayName("tenant 三个字段与 sys_tenant 一致（rootNodeId 与 tenantId 同值，契约 §2.1）")
    void tenantBlockIsFilled() throws Exception {
        JsonNode tenant = me(AuthFixtures.TEACHER_USERNAME).path("data").path("tenant");

        assertThat(tenant.path("tenantId").asText()).isEqualTo(String.valueOf(AuthFixtures.TENANT_ID));
        assertThat(tenant.path("rootNodeId").asText()).isEqualTo(String.valueOf(AuthFixtures.ROOT_NODE));
        assertThat(tenant.path("name").asText()).isEqualTo("IT 测试机构");
    }

    private JsonNode me(String username) throws Exception {
        String token = client.loginForToken(username, AuthFixtures.PASSWORD);
        JsonNode response = client.getWithToken("/api/v1/auth/me", token);
        assertThat(response.path("code").asInt()).isEqualTo(200);
        return response;
    }
}
