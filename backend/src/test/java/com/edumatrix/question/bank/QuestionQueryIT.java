package com.edumatrix.question.bank;

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
 * 题目读侧四接口（03-04 §2.1 / §2.4 / §2.5 / §2.6，目录 5 / 8 / 9 / 10）。
 */
class QuestionQueryIT extends QuestionIntegrationTestBase {

    private static final String QUESTIONS = "/api/v1/question/questions";

    // ================================================================ 接口 5 分页

    @Test
    @DisplayName("接口 5 返回自有 ∪ 被授权，逐行标 grantType；【不回溯祖先链】")
    void pageReturnsOwnedUnionGranted() throws Exception {
        questionFixtures.grantQuestion(QuestionFixtures.Q_TA, QuestionFixtures.TB,
                QuestionFixtures.TENANT_ID);

        JsonNode teacherB = data(getWithToken(QUESTIONS, loginAs(QuestionFixtures.TB)));
        assertEquals(1, teacherB.path("total").asInt(), "TB 只被授权了一道题");
        assertEquals(String.valueOf(QuestionFixtures.Q_TA),
                teacherB.path("list").get(0).path("id").asText());
        assertEquals(2, teacherB.path("list").get(0).path("grantType").asInt(), "被授权 → 2");

        JsonNode root = data(getWithToken(QUESTIONS, loginAs(QuestionFixtures.ROOT)));
        assertFalse(root.toString().contains(String.valueOf(QuestionFixtures.Q_TA)),
                "ROOT 是 TA 的祖先，但未被显式授权 —— 上级拥有 ≠ 我自动拥有的反面同样成立");
    }

    @Test
    @DisplayName("接口 5 材料题【只出父题】（固定过滤 parent_id = 0）")
    void pageShowsParentOnly() throws Exception {
        JsonNode page = data(getWithToken(QUESTIONS, loginAs(QuestionFixtures.ROOT)));
        assertTrue(page.toString().contains(String.valueOf(QuestionFixtures.Q_MATERIAL)));
        assertFalse(page.toString().contains(String.valueOf(QuestionFixtures.Q_CHILD_1)),
                "子题不该在列表里单独出现（03-04 §2.1）");
    }

    @Test
    @DisplayName("接口 5 grantType 筛选：1 仅自有 / 2 仅被授权 / 不传取并集")
    void pageFiltersByGrantType() throws Exception {
        questionFixtures.grantQuestion(QuestionFixtures.Q_TA, QuestionFixtures.TB,
                QuestionFixtures.TENANT_ID);
        String tb = loginAs(QuestionFixtures.TB);
        assertEquals(0, data(getWithToken(QUESTIONS + "?grantType=1", tb)).path("total").asInt());
        assertEquals(1, data(getWithToken(QUESTIONS + "?grantType=2", tb)).path("total").asInt());
        assertEquals(1, data(getWithToken(QUESTIONS, tb)).path("total").asInt());
    }

    @Test
    @DisplayName("接口 5 分类筛选【含全部子孙分类】（03-04 §2.1）")
    void pageCategoryIncludesDescendants() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        // CAT_MATH 直属只有材料题父题；其子分类 CAT_ALGEBRA 下有 Q_SINGLE
        JsonNode math = data(getWithToken(QUESTIONS + "?categoryId=" + QuestionFixtures.CAT_MATH, token));
        assertTrue(math.toString().contains(String.valueOf(QuestionFixtures.Q_SINGLE)),
                "按父分类筛必须带出子孙分类下的题");
        JsonNode algebra = data(getWithToken(
                QUESTIONS + "?categoryId=" + QuestionFixtures.CAT_ALGEBRA, token));
        assertFalse(algebra.toString().contains(String.valueOf(QuestionFixtures.Q_MATERIAL)),
                "按子分类筛不该带出父分类的题");
    }

    @Test
    @DisplayName("接口 5 行里带 scoreDefault（在版本表）与 ownerNodeName / creatorName")
    void pageRowCarriesJoinedFields() throws Exception {
        JsonNode row = data(getWithToken(QUESTIONS + "?questionType=1", loginAs(QuestionFixtures.ROOT)))
                .path("list").get(0);
        assertEquals(5.0, row.path("scoreDefault").asDouble(),
                "scoreDefault 在版本表里，列表要 JOIN 才拿得到（03-04 §2.1 示例也是 5.0）");
        assertEquals("IT10 题库机构", row.path("ownerNodeName").asText());
        assertEquals("IT10 题库机构", row.path("creatorName").asText());
    }

    @Test
    @DisplayName("接口 5 跨租户不可见；pageSize 上限 100")
    void pageIsTenantIsolatedAndCapped() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        assertFalse(data(getWithToken(QUESTIONS, token)).toString()
                .contains(String.valueOf(QuestionFixtures.Q_OTHER)));
        assertEquals(200, code(getWithToken(QUESTIONS + "?pageSize=9999", token)));
    }

    // ================================================================ 接口 8 详情

    @Test
    @DisplayName("接口 8 材料题父题：附全部子题，顺序按父题版本的 childOrder")
    void materialDetailCarriesChildrenInChildOrder() throws Exception {
        JsonNode detail = data(getWithToken(QUESTIONS + "/" + QuestionFixtures.Q_MATERIAL,
                loginAs(QuestionFixtures.ROOT)));
        assertEquals(2, detail.path("childQuestions").size());
        assertEquals(String.valueOf(QuestionFixtures.Q_CHILD_1),
                detail.path("childQuestions").get(0).path("id").asText());
        assertEquals(1, detail.path("childQuestions").get(0).path("sort").asInt());
        assertEquals(String.valueOf(QuestionFixtures.Q_CHILD_2),
                detail.path("childQuestions").get(1).path("id").asText());
        assertTrue(detail.path("version").path("correctAnswer").isNull(),
                "材料题父题不存答案（契约 §5）");
    }

    @Test
    @DisplayName("接口 8 传子题 ID：返回子题并附 parentId（详情【允许】子题 ID，与接口 11/12 不同）")
    void childDetailIsAllowed() throws Exception {
        JsonNode detail = data(getWithToken(QUESTIONS + "/" + QuestionFixtures.Q_CHILD_1,
                loginAs(QuestionFixtures.ROOT)));
        assertEquals(String.valueOf(QuestionFixtures.Q_MATERIAL), detail.path("parentId").asText());
        assertEquals("B", detail.path("version").path("correctAnswer").path("answer").asText());
        assertTrue(detail.path("childQuestions").isMissingNode(),
                "普通题/子题的响应里不该出现 childQuestions 这个键（NON_NULL）");
    }

    @Test
    @DisplayName("接口 8 是教师侧接口：含 correctAnswer 与 analysis")
    void detailCarriesAnswerAndAnalysis() throws Exception {
        JsonNode detail = data(getWithToken(QUESTIONS + "/" + QuestionFixtures.Q_SINGLE,
                loginAs(QuestionFixtures.ROOT)));
        assertEquals("A", detail.path("version").path("correctAnswer").path("answer").asText());
        assertEquals("因式分解", detail.path("version").path("analysis").asText());
    }

    @Test
    @DisplayName("接口 8 不可见 / 跨租户 / 不存在 —— 三者同一个响应")
    void detailHidesExistence() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        HttpOutcome invisible = outcome("GET", QUESTIONS + "/" + QuestionFixtures.Q_TA, token, null);
        HttpOutcome crossTenant = outcome("GET", QUESTIONS + "/" + QuestionFixtures.Q_OTHER, token, null);
        HttpOutcome absent = outcome("GET", QUESTIONS + "/9999999999999999", token, null);
        assertEquals(absent, invisible);
        assertEquals(absent, crossTenant);
    }

    @Test
    @DisplayName("接口 8 被授权者可读父题【也可读子题】—— 授权粒度是父题")
    void grantedUserReadsWholeMaterialQuestion() throws Exception {
        questionFixtures.grantQuestion(QuestionFixtures.Q_MATERIAL, QuestionFixtures.TB,
                QuestionFixtures.TENANT_ID);
        String tb = loginAs(QuestionFixtures.TB);
        assertEquals(200, code(getWithToken(QUESTIONS + "/" + QuestionFixtures.Q_MATERIAL, tb)));
        assertEquals(200, code(getWithToken(QUESTIONS + "/" + QuestionFixtures.Q_CHILD_1, tb)),
                "授权了父题却读不到子题 —— 被授权方拿到的是半份材料题，而接口 200");
    }

    // ================================================================ 接口 9 版本列表

    @Test
    @DisplayName("接口 9 按 version 倒序，isCurrent 标当前版本；不含完整 content")
    void versionListIsDescendingWithIsCurrent() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        assertEquals(200, code(putWithToken(QUESTIONS + "/" + QuestionFixtures.Q_SINGLE, token,
                "{\"analysis\":\"第二版解析\"}")));

        JsonNode versions = data(getWithToken(
                QUESTIONS + "/" + QuestionFixtures.Q_SINGLE + "/versions", token));
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).path("version").asInt());
        assertTrue(versions.get(0).path("isCurrent").asBoolean());
        assertEquals(1, versions.get(1).path("version").asInt());
        assertFalse(versions.get(1).path("isCurrent").asBoolean());
        assertTrue(versions.get(0).path("content").isMissingNode(),
                "版本列表不含完整 content（要完整快照走接口 10）");
    }

    // ================================================================ 接口 10 版本快照

    @Test
    @DisplayName("接口 10 返回不可变快照；版本不存在 → 30007")
    void snapshotOr30007() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        JsonNode snapshot = data(getWithToken(
                QUESTIONS + "/" + QuestionFixtures.Q_SINGLE + "/versions/1", token));
        assertEquals(1, snapshot.path("version").asInt());
        assertEquals("A", snapshot.path("correctAnswer").path("answer").asText());

        assertEquals(ErrorCode.QUESTION_VERSION_NOT_FOUND.getCode(), code(getWithToken(
                QUESTIONS + "/" + QuestionFixtures.Q_SINGLE + "/versions/99", token)));
    }

    /**
     * <b>顺序是死规定。</b>
     *
     * <p>若先查版本再判可见性，那么「不可见的题 + 存在的版本号」会返回 30007
     * 而「不可见的题 + 不存在的版本号」也返回 30007 —— 看似一样；
     * 但「不存在的题」返回 404、「不可见的题」返回 30007，两者一比就知道那道题存在。
     * 本条断言不可见的题与不存在的题给出<b>同一个响应</b>。
     */
    @Test
    @DisplayName("接口 10 可见性 404 在 30007 之前判 —— 否则 30007 成了存在性探针")
    void visibilityIsCheckedBeforeVersionExistence() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        HttpOutcome invisibleExistingVersion = outcome("GET",
                QUESTIONS + "/" + QuestionFixtures.Q_TA + "/versions/1", token, null);
        HttpOutcome absent = outcome("GET",
                QUESTIONS + "/9999999999999999/versions/1", token, null);
        assertEquals(absent, invisibleExistingVersion,
                "不可见的题目泄露了它的版本存在与否");
    }
}
