package com.edumatrix.question.category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.question.support.QuestionFixtures;
import com.edumatrix.question.support.QuestionIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题库分类四个接口（03-04 §1，目录 1~4）。
 */
class QuestionCategoryIT extends QuestionIntegrationTestBase {

    private static final String CATEGORIES = "/api/v1/question/categories";

    // ================================================================ 接口 1 树

    @Test
    @DisplayName("接口 1 分类树：questionCount 是【直属】计数，材料题只计父题")
    void treeCountsDirectQuestionsOnly() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        JsonNode tree = data(getWithToken(CATEGORIES, token));

        JsonNode math = findById(tree, QuestionFixtures.CAT_MATH);
        // CAT_MATH 直属：Q_MATERIAL 父题（两个子题 parent_id != 0，不计）
        assertEquals(1, math.path("questionCount").asInt(),
                "材料题子题也挂在 CAT_MATH 上，但只该计父题（03-04 §1.1 脚注）");
        JsonNode algebra = findById(math.path("children"), QuestionFixtures.CAT_ALGEBRA);
        assertEquals(2, algebra.path("questionCount").asInt(), "Q_SINGLE + Q_TA");
    }

    @Test
    @DisplayName("接口 1 keyword：命中节点连同其祖先链一起返回（03-04 §1.1）")
    void keywordKeepsAncestorChain() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        JsonNode tree = data(getWithToken(CATEGORIES + "?keyword=代数", token));
        assertEquals(1, tree.size(), "只该保留命中链");
        assertEquals(String.valueOf(QuestionFixtures.CAT_MATH), tree.get(0).path("id").asText(),
                "祖先「数学」必须在，否则「代数」在树上无处安放");
        assertEquals(String.valueOf(QuestionFixtures.CAT_ALGEBRA),
                tree.get(0).path("children").get(0).path("id").asText());
    }

    @Test
    @DisplayName("接口 1 租户隔离：看不到另一个机构的分类")
    void treeIsTenantIsolated() throws Exception {
        String tree = data(getWithToken(CATEGORIES, loginAs(QuestionFixtures.ROOT))).toString();
        assertTrue(tree.contains(String.valueOf(QuestionFixtures.CAT_MATH)));
        assertFalse(tree.contains(String.valueOf(QuestionFixtures.CAT_OTHER)),
                "分类树不做节点级过滤，但租户隔离由插件强制（03-04 §1.1 权限栏）");
    }

    @Test
    @DisplayName("接口 1 分类树【不做节点级过滤】：教师看到的与管理员相同")
    void treeIsNotFilteredByNode() throws Exception {
        String adminTree = data(getWithToken(CATEGORIES, loginAs(QuestionFixtures.ROOT))).toString();
        String teacherTree = data(getWithToken(CATEGORIES, loginAs(QuestionFixtures.TA))).toString();
        assertEquals(adminTree, teacherTree,
                "qb_category 是组织无关的目录结构，租户内共享（03-04 §1.1）");
    }

    // ================================================================ 接口 2 新增

    @Test
    @DisplayName("接口 2 新增分类：管理员可建；同级重名 → 400")
    void createAndRejectDuplicateName() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        JsonNode created = postWithToken(CATEGORIES, token,
                "{\"parentId\":\"0\",\"categoryName\":\"物理\",\"sort\":3}");
        assertEquals(200, code(created));
        assertTrue(data(created).path("id").asText().length() > 10);

        assertEquals(ErrorCode.BAD_REQUEST.getCode(), code(postWithToken(CATEGORIES, token,
                "{\"parentId\":\"0\",\"categoryName\":\"物理\"}")));
    }

    @Test
    @DisplayName("接口 2 父分类不存在 → 404")
    void createUnderMissingParent() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        assertEquals(ErrorCode.NOT_FOUND.getCode(), code(postWithToken(CATEGORIES, token,
                "{\"parentId\":\"9999999999999999\",\"categoryName\":\"新分类\"}")));
    }

    /**
     * <b>F-72 的执行侧。</b>
     *
     * <p>在 {@code V202608200000} 之前，{@code question:category:add / edit / remove}
     * 是绑给 teacher 的（契约 §10 附表 A 与初始化脚本<b>一致地</b>如此），
     * 只写 {@code @SaCheckPermission} 的话教师<b>会通过</b>。
     * 把那条迁移删掉、库重建 → 本条立刻红。
     */
    @Test
    @DisplayName("F-72：教师【不能】新增/修改/删除分类 → 403（靠 sys_role_menu 绑定，不靠角色门）")
    void teacherCannotWriteCategories() throws Exception {
        String teacher = loginAs(QuestionFixtures.TA);
        assertEquals(ErrorCode.FORBIDDEN.getCode(), code(postWithToken(CATEGORIES, teacher,
                "{\"parentId\":\"0\",\"categoryName\":\"教师建的分类\"}")));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), code(putWithToken(
                CATEGORIES + "/" + QuestionFixtures.CAT_EMPTY, teacher,
                "{\"categoryName\":\"改名\"}")));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), code(deleteWithToken(
                CATEGORIES + "/" + QuestionFixtures.CAT_EMPTY, teacher)));
    }

    @Test
    @DisplayName("教师【可以】读分类树 —— 只读那一半没有被 F-72 波及")
    void teacherCanStillReadTree() throws Exception {
        assertEquals(200, code(getWithToken(CATEGORIES, loginAs(QuestionFixtures.TA))));
    }

    // ================================================================ 接口 3 修改

    @Test
    @DisplayName("接口 3 改名与移动；不存在 → 404")
    void updateCategory() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        assertEquals(200, code(putWithToken(CATEGORIES + "/" + QuestionFixtures.CAT_EMPTY, token,
                "{\"categoryName\":\"函数与导数\",\"sort\":9}")));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), code(putWithToken(
                CATEGORIES + "/9999999999999999", token, "{\"categoryName\":\"x\"}")));
    }

    @Test
    @DisplayName("接口 3 不可移动到自身或其子孙 → 400（成环会让整棵子树从树上消失）")
    void cannotMoveIntoOwnSubtree() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), code(putWithToken(
                CATEGORIES + "/" + QuestionFixtures.CAT_MATH, token,
                "{\"parentId\":\"" + QuestionFixtures.CAT_MATH + "\"}")));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), code(putWithToken(
                CATEGORIES + "/" + QuestionFixtures.CAT_MATH, token,
                "{\"parentId\":\"" + QuestionFixtures.CAT_ALGEBRA + "\"}")));
    }

    @Test
    @DisplayName("接口 3 跨租户 → 404，与「不存在」同一个响应（不暴露存在性）")
    void updateCrossTenantIsIndistinguishable() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        HttpOutcome crossTenant = outcome("PUT", CATEGORIES + "/" + QuestionFixtures.CAT_OTHER,
                token, "{\"categoryName\":\"x\"}");
        HttpOutcome absent = outcome("PUT", CATEGORIES + "/9999999999999999",
                token, "{\"categoryName\":\"x\"}");
        assertEquals(absent, crossTenant);
    }

    // ================================================================ 接口 4 删除

    @Test
    @DisplayName("接口 4 空分类可删；有子分类 → 30004；有题目 → 30004")
    void deleteOnlyWhenEmpty() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        assertEquals(ErrorCode.QUESTION_CATEGORY_NOT_EMPTY.getCode(), code(deleteWithToken(
                CATEGORIES + "/" + QuestionFixtures.CAT_MATH, token)), "有子分类");
        assertEquals(ErrorCode.QUESTION_CATEGORY_NOT_EMPTY.getCode(), code(deleteWithToken(
                CATEGORIES + "/" + QuestionFixtures.CAT_ALGEBRA, token)), "有题目");
        assertEquals(200, code(deleteWithToken(CATEGORIES + "/" + QuestionFixtures.CAT_EMPTY, token)));
    }

    @Test
    @DisplayName("接口 4 的题目计数【含材料题子题】——只数父题会把子题挂到不存在的分类上")
    void deleteCountsChildQuestionsToo() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        // 把 CAT_MATH 的父题挪走，只留下两个子题
        jdbcTemplate.update("UPDATE qb_question SET category_id = ? WHERE id = ?",
                QuestionFixtures.CAT_ALGEBRA, QuestionFixtures.Q_MATERIAL);
        assertEquals(ErrorCode.QUESTION_CATEGORY_NOT_EMPTY.getCode(), code(deleteWithToken(
                CATEGORIES + "/" + QuestionFixtures.CAT_MATH, token)),
                "分类下还剩两个子题 —— 删掉分类它们就挂空了");
    }

    private static JsonNode findById(JsonNode array, long id) {
        for (JsonNode node : array) {
            if (node.path("id").asText().equals(String.valueOf(id))) {
                return node;
            }
        }
        throw new AssertionError("分类树里找不到 id=" + id + "，实际：" + array);
    }
}
