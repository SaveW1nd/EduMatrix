package com.edumatrix.org;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.org.support.OrgFixtures;
import com.edumatrix.org.support.OrgIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 03-02 §3.4 的<b>11 条校验逐条</b>，各自错误码不同。
 *
 * <p>这些码不是可以合并的同义词：前端据它们给出的提示语完全不同
 * （「教师名下只能加学员」vs「学员不能再带人」vs「请重新选择节点」），
 * 合并任意两个都会让某一句提示消失。
 */
class NodeMoveValidationIT extends OrgIntegrationTestBase {

    @Test
    @DisplayName("校验 1：被移动节点或目标父节点不存在 → 10101")
    void missingNodeIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);
        long ghost = 1962000000000099999L;

        assertThat(code(move(token, ghost, OrgFixtures.A2))).isEqualTo(10101);
        assertThat(code(move(token, OrgFixtures.T1, ghost))).isEqualTo(10101);
    }

    @Test
    @DisplayName("校验 2：被移动节点不在我的子树内 → 10107（A2 管不到 A1 底下的 T1）")
    void movingNodeOutsideMySubtreeIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.A2);

        assertThat(code(move(token, OrgFixtures.T1, OrgFixtures.TX))).isEqualTo(10107);
        assertThat(orgFixtures.parentOf(OrgFixtures.T1)).isEqualTo(OrgFixtures.A1);
    }

    @Test
    @DisplayName("校验 2：不能把自己所在节点搬走 → 10107")
    void movingMyOwnNodeIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.A2);

        // A2 自己在自己的子树内（子树含自身），挡住它的是「不是我自己」那一条
        assertThat(code(move(token, OrgFixtures.A2, OrgFixtures.TX))).isEqualTo(10107);
    }

    @Test
    @DisplayName("校验 2：租户根节点不可被移动 → 10107")
    void movingTenantRootIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        // ROOT 既是操作人自己所在节点，也是租户根 —— 两条都拦得住，码相同
        assertThat(code(move(token, OrgFixtures.ROOT, OrgFixtures.A2))).isEqualTo(10107);
        assertThat(orgFixtures.parentOf(OrgFixtures.ROOT)).isZero();
    }

    @Test
    @DisplayName("校验 3：目标父节点不在我的子树内 → 10107（跨子树搬运在此被拒）")
    void targetParentOutsideMySubtreeIsRejected() throws Exception {
        // A1 管得到 T1，但管不到 A2 那一支 —— 把自己的节点搬到别人那边同样要拒
        String token = loginAs(OrgFixtures.A1);

        assertThat(code(move(token, OrgFixtures.T1, OrgFixtures.TX))).isEqualTo(10107);
        assertThat(orgFixtures.parentOf(OrgFixtures.T1)).isEqualTo(OrgFixtures.A1);
    }

    @Test
    @DisplayName("校验 5：目标父是教师节点时，被移动节点必须是学生 → 10105")
    void teacherParentAcceptsOnlyStudents() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        // 把教师 T2 挂到教师 T3 下
        // （F-114 之前这里还有一条「把管理员 A3 挂到教师 T3 下」，A3 是嵌套管理员，树里已无此节点）
        assertThat(code(move(token, OrgFixtures.T2, OrgFixtures.T3))).isEqualTo(10105);
        // 学生可以（对照组）
        assertThat(code(move(token, OrgFixtures.S1, OrgFixtures.T3))).isEqualTo(200);
    }

    @Test
    @DisplayName("校验 6：目标父是学生节点时一律拒绝 → 10106（学生必为叶子）")
    void studentParentIsAlwaysRejected() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(code(move(token, OrgFixtures.S2, OrgFixtures.S1))).isEqualTo(10106);
        assertThat(code(move(token, OrgFixtures.T2, OrgFixtures.S1))).isEqualTo(10106);
        assertThat(orgFixtures.childCountOf(OrgFixtures.S1)).isZero();
    }

    @Test
    @DisplayName("校验 8：目标父节点已停用 → 10109")
    void disabledTargetParentIsRejected() throws Exception {
        String adminToken = loginAs(OrgFixtures.ROOT);
        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.A2 + "/status",
                adminToken, "{\"status\":1}"))).isEqualTo(200);

        assertThat(code(move(adminToken, OrgFixtures.T1, OrgFixtures.A2))).isEqualTo(10109);
        assertThat(orgFixtures.parentOf(OrgFixtures.T1)).isEqualTo(OrgFixtures.A1);
    }

    @Test
    @DisplayName("校验 9：目标父节点下已有同名节点 → 10102")
    void duplicateNameUnderTargetParentIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);
        // TX 在 A2 下，T3 在 A1 下 —— 先把 TX 改成和 T3 同名。
        // 这一步本身合法：同级唯一约束只管【同一个父节点】下
        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.TX, token,
                "{\"nodeName\":\"赵敏\"}"))).isEqualTo(200);

        // 再把 T3 移到 A2 下，就撞上了同级重名
        assertThat(code(move(token, OrgFixtures.T3, OrgFixtures.A2))).isEqualTo(10102);
        assertThat(orgFixtures.parentOf(OrgFixtures.T3)).isEqualTo(OrgFixtures.A1);
    }

    @Test
    @DisplayName("校验 10：被移动学生已归档/已退课 → 10203")
    void archivedStudentCannotBeMoved() throws Exception {
        orgFixtures.setStudentStatus(OrgFixtures.S1, 2);
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(code(move(token, OrgFixtures.S1, OrgFixtures.T3))).isEqualTo(10203);
        assertThat(orgFixtures.parentOf(OrgFixtures.S1)).isEqualTo(OrgFixtures.T1);
    }

    @Test
    @DisplayName("校验 11：目标父节点与当前上级相同 → 10205")
    void movingToTheSameParentIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(code(move(token, OrgFixtures.T1, OrgFixtures.A1))).isEqualTo(10205);
        assertThat(orgFixtures.changeLogCount(OrgFixtures.T1)).isZero();
    }

    @Test
    @DisplayName("校验顺序：越界（10107）先于成环（10103）—— 两条同时违反时报越界")
    void scopeIsCheckedBeforeCycle() throws Exception {
        // A2 既够不到 A1（越界），把 A1 移到它自己的后代 T1 下又会成环。
        // §3.4 的校验顺序表里 2/3 在 4 之前，所以应当报 10107
        String token = loginAs(OrgFixtures.A2);

        JsonNode response = move(token, OrgFixtures.A1, OrgFixtures.T1);

        assertThat(code(response)).isEqualTo(10107);
    }

    // =====================================================================
    // F-114 定案二：机构下只允许一层管理员（03-02 接口 4 移动节点侧）
    // =====================================================================

    @Test
    @DisplayName("F-114：把管理员挪到另一个分校管理员之下 → 10104（与建人侧同一个码）")
    void movingAdminUnderAnotherAdminIsRejected() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(code(move(token, OrgFixtures.A1, OrgFixtures.A2)))
                .as("A2 不是机构根，它下面不能有第二层管理员")
                .isEqualTo(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID.getCode());
        assertThat(orgFixtures.parentOf(OrgFixtures.A1)).isEqualTo(OrgFixtures.ROOT);
        assertThat(orgFixtures.changeLogCount(OrgFixtures.A1)).isZero();
    }

    @Test
    @DisplayName("F-114 的推论：管理员节点【等于不可移动】—— 唯一合法父节点就是它现在的父节点")
    void adminNodeIsEffectivelyImmovable() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        // 合法父节点只剩机构根一个（教师/学生下更不行），而它本来就挂在机构根下，
        // 于是撞的是校验 11「目标父节点不等于当前上级」。
        // 【这不是缺陷，是定案的直接结果】—— 需方在定案里就点明了「等于不可移动」
        assertThat(code(move(token, OrgFixtures.A1, OrgFixtures.ROOT))).isEqualTo(10205);
        assertThat(code(move(token, OrgFixtures.A1, OrgFixtures.A2)))
                .isEqualTo(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID.getCode());
        // 目标教师必须取【A1 子树之外】的 TX：取 A1 名下的教师会先撞成环（10103）
        assertThat(code(move(token, OrgFixtures.A1, OrgFixtures.TX))).isEqualTo(10105);
        assertThat(orgFixtures.parentOf(OrgFixtures.A1)).isEqualTo(OrgFixtures.ROOT);
    }

    @Test
    @DisplayName("非 org_admin 调移动接口 → 403（权限的真相在菜单绑定数据里）")
    void teacherCannotMoveNodes() throws Exception {
        String token = loginAs(OrgFixtures.T3);

        var result = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/org/nodes/" + OrgFixtures.S6 + "/move")
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(moveBody(OrgFixtures.TX))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }
}
