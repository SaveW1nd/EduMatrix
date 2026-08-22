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
    /**
     * ⚠ <b>本用例的 403 在 F-114 之后是【过定】的，它不再能证明 F-72</b>。
     *
     * <p>教师 {@code TA} 既没有 {@code question:category:*} 绑定（F-72），
     * <b>也不在机构根节点上</b>（F-114 收窄）—— 两道闸都会给 403，
     * 而它断的只是「结果是 403」。<b>实测</b>：把那三条绑定加回 {@code sys_role_menu}，
     * 本用例<b>照样全绿</b>（M62）。
     *
     * <p>所以 F-72 那一半改由 {@link #teacherHasNoCategoryWritePerms()} 守 ——
     * 它走 {@code /auth/me}，读的是 {@code sys_role_menu → sys_menu.perms} 这条真实链路，
     * <b>与机构根那道闸无关</b>。本用例保留，因为「教师拿不到这三个端点」这个<b>结果</b>
     * 仍然要断言，只是别再指望它证明成因。
     */
    @Test
    @DisplayName("教师【不能】新增/修改/删除分类 → 403（结果断言；成因见 teacherHasNoCategoryWritePerms）")
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
    @DisplayName("⚠ F-72 的成因判据：教师的 perms 里【没有】那三个 question:category:*")
    void teacherHasNoCategoryWritePerms() throws Exception {
        // 【为什么要绕 /auth/me 而不是打写端点】写端点上现在压着两道闸（perms + 机构根），
        // 教师两道都过不了，403 说明不了是哪一道。而 /auth/me 的 perms 直接来自
        // sys_role_menu → sys_menu.perms —— F-72 说的「真相」就在那里，机构根那道闸碰不到它。
        //
        // 【AuthMeIT.teacherPerms 那个计数不够】它断的是「教师有 61 个权限位」，
        // 加回来会红没错，但它是【计数不是身份】：删一个、加一个，数字不变，全绿。
        assertFalse(permsOf(QuestionFixtures.TA).stream()
                        .anyMatch(p -> p.startsWith("question:category:")),
                "F-72 撤的是 sys_role_menu 里那三行绑定（V202608200000），"
                        + "不是代码里的角色门 —— 判据必须落在 perms 上");
    }

    @Test
    @DisplayName("教师【可以】读分类树 —— 只读那一半没有被 F-72 波及")
    void teacherCanStillReadTree() throws Exception {
        assertEquals(200, code(getWithToken(CATEGORIES, loginAs(QuestionFixtures.TA))));
    }

    // ================================================================ F-114 分类写收窄

    /**
     * <b>三个写口拆成三条用例，不合并成一条</b>。
     *
     * <p>合并的话第一个断言一挂，后面两个<b>根本跑不到</b> —— 变异 M61
     * （只给「新增」接闸、「修改/删除」漏接）本该让后两条红，
     * 合并版只会红在「修改」那一句，而「删除」到底有没有闸<b>那一轮压根没验过</b>。
     * 实测过：合并版下 M60 与 M61 都只红 1 条。
     */
    @Test
    @DisplayName("⚠ F-114 新增分类【仅机构根】：分校管理员 403、机构根 200（两侧都断）")
    void createCategoryIsOrgRootOnly() throws Exception {
        // A1 是机构根下的分校管理员，持 org_admin —— perms 那道闸他过得了，
        // 断在这里的必须是机构根那道
        assertEquals(ErrorCode.FORBIDDEN.getCode(), code(postWithToken(CATEGORIES,
                        loginAs(QuestionFixtures.A1),
                        "{\"parentId\":\"0\",\"categoryName\":\"分校建的分类\"}")),
                "他新建一个分类【放不进任何题】—— F-114 之后他对题库已经完全只读，"
                        + "这是个只覆盖了半个动作的残缺权限");

        assertEquals(200, code(postWithToken(CATEGORIES, loginAs(QuestionFixtures.ROOT),
                        "{\"parentId\":\"0\",\"categoryName\":\"机构根建的分类\"}")),
                "这一侧不写，等于把新增分类整个关掉也全绿");
    }

    @Test
    @DisplayName("⚠ F-114 修改分类【仅机构根】：分校管理员 403、机构根 200（两侧都断）")
    void updateCategoryIsOrgRootOnly() throws Exception {
        assertEquals(ErrorCode.FORBIDDEN.getCode(), code(putWithToken(
                        CATEGORIES + "/" + QuestionFixtures.CAT_EMPTY,
                        loginAs(QuestionFixtures.A1), "{\"categoryName\":\"分校改的名\"}")),
                "他改一个分类名【改的是别人题目的归类】—— 分类树是全租户共享的一棵（PRD:145）");

        assertEquals(200, code(putWithToken(CATEGORIES + "/" + QuestionFixtures.CAT_EMPTY,
                loginAs(QuestionFixtures.ROOT), "{\"categoryName\":\"机构根改的名\"}")));
    }

    @Test
    @DisplayName("⚠ F-114 删除分类【仅机构根】：分校管理员 403、机构根 200（两侧都断）")
    void deleteCategoryIsOrgRootOnly() throws Exception {
        // 【事实修正】删除本来就有引用保护：分类下有子分类或题目一律 30004，
        // 所以只有空分类删得掉，删不掉别人正在用的分类。
        // 收窄它是为了三个写口一致（半个权限要么补全要么去掉），
        // 不是因为「分校管理员能删掉别人题目的归类」—— 那句是说重了
        assertEquals(ErrorCode.FORBIDDEN.getCode(), code(deleteWithToken(
                CATEGORIES + "/" + QuestionFixtures.CAT_EMPTY, loginAs(QuestionFixtures.A1))));

        assertEquals(200, code(deleteWithToken(
                        CATEGORIES + "/" + QuestionFixtures.CAT_EMPTY,
                        loginAs(QuestionFixtures.ROOT))),
                "这一侧不写，等于把删除分类整个关掉也全绿");
    }

    @Test
    @DisplayName("F-114：分类的【读】一个都不动 —— 分校管理员照样看得见整棵树")
    void categoryReadStillWorksForSubAdmin() throws Exception {
        // 收窄的只有写。分校管理员要能按分类挑题给名下学员组卷，
        // 读也关掉的话，本轮就不是「收窄写权限」而是「把题库对他关掉」了
        JsonNode tree = getWithToken(CATEGORIES, loginAs(QuestionFixtures.A1));
        assertEquals(200, code(tree));
        assertTrue(tree.toString().contains("数学"), "分类树是组织无关的目录结构，不做节点级过滤");
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
    @DisplayName("⚠ 接口 3 库里【已经成环】时 → 400 而不是转圈：那条兜底此前一次都没执行到")
    void cyclicCategoryDataIsRejectedNotHung() throws Exception {
        // 【自查揪出来的】把「分类层级超过 32 层，疑似成环」那条兜底 throw 整个删掉，
        // 本类 18 条【全绿】—— 它一次都没被执行到。
        // 原因是 F-115 归纳的【第三种形态】：assertMovable 每次移动都查一遍环，
        // 走接口根本造不出环，于是那条兜底永远够不着。
        // 它要防的是【库里本来就有环】的数据（手工改库、并发写、迁移写错），
        // 所以只能直接插一对互指的分类来验。
        questionFixtures.seedCyclicCategories();

        // 把 CAT_EMPTY 移到环里 —— 向上回溯 CYCLE_A → CYCLE_B → CYCLE_A … 永不到顶
        JsonNode response = putWithToken(CATEGORIES + "/" + QuestionFixtures.CAT_EMPTY,
                loginAs(QuestionFixtures.ROOT),
                "{\"parentId\":\"" + QuestionFixtures.CYCLE_A + "\"}");

        assertEquals(400, code(response),
                "兜底没有的话这里会一直往上爬 32 层然后【静默放行】，"
                        + "把一条挂在环上的分类写进库 —— 而接口返回 200");
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
