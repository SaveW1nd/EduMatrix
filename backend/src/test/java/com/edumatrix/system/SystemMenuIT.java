package com.edumatrix.system;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.system.support.SystemFixtures;
import com.edumatrix.system.support.SystemIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单管理的验收（03-01 §4.1~§4.4）。
 *
 * <p>§4.2/§4.3/§4.4 是<b>平台级维护接口</b>，「org_admin 及以下调用返回 403」（§4.2 原文）；
 * 只有 §4.1 对 org_admin 开放，且只返回<b>他自身权限范围内</b>的菜单树（防提权）——
 * 那个树是"给角色分配菜单"弹窗的数据源，给全量等于让他在界面上勾选自己没有的菜单。
 */
class SystemMenuIT extends SystemIntegrationTestBase {

    private static final String MENUS = "/api/v1/system/menus";

    // =====================================================================
    // §4.1 菜单树
    // =====================================================================

    @Test
    @DisplayName("§4.1｜super_admin 得到全量树；org_admin 的树里没有他没有的按钮")
    void treeIsPrunedForOrgAdmin() throws Exception {
        List<String> superPerms = collectPerms(
                client.getWithToken(MENUS + "/tree", superAdminToken()));
        List<String> orgPerms = collectPerms(
                client.getWithToken(MENUS + "/tree", orgAdminToken()));

        assertThat(superPerms).contains("system:user:add", "system:menu:add");
        // 防提权：system:user:add 只绑了 super_admin，org_admin 的树里不该出现它
        assertThat(orgPerms).doesNotContain("system:user:add", "system:menu:add");
        // 对照：他确实拥有的读菜单在树里
        assertThat(orgPerms).contains("system:user:list", "system:role:list");
    }

    @Test
    @DisplayName("§4.1｜按名称筛选时保留命中节点的祖先链（否则前端拿到空树）")
    void treeKeepsAncestorsOfMatchedNodes() throws Exception {
        JsonNode response = client.getWithToken(MENUS + "/tree?menuName=重置用户密码", superAdminToken());

        assertThat(code(response)).isEqualTo(200);
        // 命中的是一个按钮（F），它的父是"用户管理"、祖父是"系统管理"。
        // 少了祖先补齐，按 parent 拼树时它找不到 parent_id = 0 的入口，
        // 结果是【接口 200、数据齐全、界面空白】
        assertThat(response.path("data")).isNotEmpty();
        assertThat(collectPerms(response)).contains("system:user:resetPwd");
    }

    // =====================================================================
    // §4.2 / §4.3 / §4.4 平台级维护接口
    // =====================================================================

    @Test
    @DisplayName("§4.2/§4.3/§4.4｜org_admin 调三个写接口一律 403")
    void menuWriteEndpointsAreSuperAdminOnly() throws Exception {
        String token = orgAdminToken();

        assertThat(code(client.postWithToken(MENUS, token, """
                {"parentId":"0","menuName":"越权菜单","menuType":"C","path":"/x"}
                """))).isEqualTo(403);

        assertThat(code(client.putWithToken(MENUS + "/" + SystemFixtures.MENU_ORPHAN, token, """
                {"parentId":"0","menuName":"越权改名","visible":1,"sort":1}
                """))).isEqualTo(403);

        assertThat(code(deleteWithToken(MENUS + "/" + SystemFixtures.MENU_ORPHAN, token)))
                .isEqualTo(403);
    }

    @Test
    @DisplayName("§4.4｜菜单存在子节点 → 10009")
    void cannotDeleteMenuWithChildren() throws Exception {
        JsonNode response = deleteWithToken(MENUS + "/" + SystemFixtures.MENU_ORPHAN,
                superAdminToken());

        assertThat(code(response)).isEqualTo(10009);
    }

    @Test
    @DisplayName("§4.4｜菜单被角色引用 → 10009")
    void cannotDeleteMenuReferencedByRole() throws Exception {
        // MENU_ORG_ADMIN_OWNED 是 system:role:list，初始化数据里绑着两个内置角色
        JsonNode response = deleteWithToken(MENUS + "/" + SystemFixtures.MENU_ORG_ADMIN_OWNED,
                superAdminToken());

        // 两个判据缺一不可：只判子节点的话，删掉一个仍被角色绑定的按钮会在
        // sys_role_menu 里留下指向已删菜单的悬挂绑定 —— 它不报错，
        // 只是 perms 装配时那一行 JOIN 不上，表现为某个角色悄悄少了一个按钮
        assertThat(code(response)).isEqualTo(10009);
    }

    @Test
    @DisplayName("§4.4｜无子节点、无角色引用的菜单 → 删除成功")
    void canDeleteOrphanMenu() throws Exception {
        String token = superAdminToken();
        // 先删子按钮，再删父菜单
        assertThat(code(deleteWithToken(MENUS + "/" + SystemFixtures.MENU_ORPHAN_CHILD, token)))
                .isEqualTo(200);
        assertThat(code(deleteWithToken(MENUS + "/" + SystemFixtures.MENU_ORPHAN, token)))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("§4.2｜perms 全局唯一 → 重复返回 400（uk_perms 是真相，这一查只为可读的提示）")
    void permsMustBeGloballyUnique() throws Exception {
        JsonNode response = client.postWithToken(MENUS, superAdminToken(), """
                {"parentId":"0","menuName":"IT 重复标识","menuType":"C",
                 "perms":"system:user:list","path":"/it-dup"}
                """);

        assertThat(code(response)).isEqualTo(400);
    }

    @Test
    @DisplayName("§4.2｜按钮下不可挂子节点 → 400")
    void buttonCannotHaveChildren() throws Exception {
        JsonNode response = client.postWithToken(MENUS, superAdminToken(), """
                {"parentId":"%d","menuName":"IT 按钮的子节点","menuType":"C","path":"/it-child"}
                """.formatted(SystemFixtures.MENU_ORPHAN_CHILD));

        assertThat(code(response)).isEqualTo(400);
    }

    @Test
    @DisplayName("§4.3｜不可移动到自身或自身后代之下 → 400（否则拼树时那一支成环或消失）")
    void cannotMoveMenuIntoOwnSubtree() throws Exception {
        JsonNode response = client.putWithToken(MENUS + "/" + SystemFixtures.MENU_ORPHAN,
                superAdminToken(), """
                {"parentId":"%d","menuName":"IT 探针菜单","visible":1,"sort":99}
                """.formatted(SystemFixtures.MENU_ORPHAN_CHILD));

        assertThat(code(response)).isEqualTo(400);
    }

    // =====================================================================

    private static List<String> collectPerms(JsonNode response) {
        List<String> perms = new ArrayList<>();
        collectPerms(response.path("data"), perms);
        return perms;
    }

    private static void collectPerms(JsonNode nodes, List<String> out) {
        nodes.forEach(node -> {
            if (!node.path("perms").isNull() && node.hasNonNull("perms")) {
                out.add(node.path("perms").asText());
            }
            collectPerms(node.path("children"), out);
        });
    }

    private String orgAdminToken() throws Exception {
        return client.loginForToken(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
    }

    private String superAdminToken() throws Exception {
        return client.loginForToken(AuthFixtures.SUPER_ADMIN_USERNAME, AuthFixtures.PASSWORD);
    }
}
