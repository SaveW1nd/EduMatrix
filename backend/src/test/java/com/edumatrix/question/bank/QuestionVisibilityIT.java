package com.edumatrix.question.bank;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.question.QuestionVersionService;
import com.edumatrix.common.question.QuestionVisibilityChecker;
import com.edumatrix.common.question.QuestionVisibilityChecker.VisibleQuestion;
import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.question.support.QuestionFixtures;
import com.edumatrix.question.support.QuestionIntegrationTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 03-04 §0.1 的题目可见性判定公式 —— 三条对外产出里最容易出静默故障的一条。
 */
class QuestionVisibilityIT extends QuestionIntegrationTestBase {

    @Autowired
    private QuestionVisibilityChecker visibilityChecker;

    @Autowired
    private QuestionVersionService versionService;

    @Autowired
    private ResourceOwnerChecker resourceOwnerChecker;

    // ================================================================ 自有

    @Test
    @DisplayName("自有：owner_node_id = 我的节点 → 可见，grantType=1")
    void ownedIsVisible() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            VisibleQuestion visible = visibilityChecker.assertVisible(QuestionFixtures.Q_SINGLE);
            assertEquals(VisibleQuestion.GRANT_TYPE_OWNED, visible.grantType());
            assertTrue(visible.owned());
        });
    }

    @Test
    @DisplayName("不回溯祖先链：ROOT 是 TA 的祖先，但【看不到】TA 自有的题 → 404")
    void ancestorCannotSeeDescendantQuestion() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            BizException error = assertThrows(BizException.class,
                    () -> visibilityChecker.assertVisible(QuestionFixtures.Q_TA));
            assertEquals(ErrorCode.NOT_FOUND.getCode(), error.getErrorCode().getCode(),
                    "上级拥有 ≠ 我自动拥有，反过来也一样：子树关系不给资源可见性（03-04 §0.1）");
        });
    }

    @Test
    @DisplayName("跨租户 → 404，与「不存在」同一个结果（不暴露存在性）")
    void crossTenantIsNotFound() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            BizException crossTenant = assertThrows(BizException.class,
                    () -> visibilityChecker.assertVisible(QuestionFixtures.Q_OTHER));
            BizException absent = assertThrows(BizException.class,
                    () -> visibilityChecker.assertVisible(9999999999999999L));
            assertEquals(absent.getErrorCode(), crossTenant.getErrorCode());
        });
    }

    // ================================================================ 被授权

    @Test
    @DisplayName("被显式授权 → 可见，grantType=2（只读可用）")
    void grantedIsVisibleAsReadOnly() throws Exception {
        questionFixtures.grantQuestion(QuestionFixtures.Q_SINGLE, QuestionFixtures.TB,
                QuestionFixtures.TENANT_ID);
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.TB, () -> {
            VisibleQuestion visible = visibilityChecker.assertVisible(QuestionFixtures.Q_SINGLE);
            assertEquals(VisibleQuestion.GRANT_TYPE_GRANTED, visible.grantType());
            assertFalse(visible.owned(), "被授权者不是 owner —— 写操作要 403，不是 200");
        });
    }

    /**
     * <b>方向被需方 2026-08-21 定案反转了</b>：原先叫 {@code expiredGrantIsInvisible}
     *（「授权已过期等同未授权 → 404」）。授权取消有效期后，{@code valid_end} 在过去的行
     * 与永久行<b>没有任何区别</b>。
     *
     * <p><b>不删掉而是反向断言</b>：这是<b>题目</b>那条可见性链上唯一带过期行的用例 ——
     * 模块 11 的 {@code GrantNoValidityIT} 钉的是课程与公共层，
     * 删掉它就等于题目这一侧没人验「谓词真的删干净了」。
     */
    @Test
    @DisplayName("⚠ 反转：valid_end 在过去的授权行【照常可见】（需方定案取消有效期）")
    void expiredGrantIsStillVisible() throws Exception {
        questionFixtures.grantQuestionExpired(QuestionFixtures.Q_SINGLE, QuestionFixtures.TB,
                QuestionFixtures.TENANT_ID);
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.TB, () -> {
            VisibleQuestion visible = visibilityChecker.assertVisible(QuestionFixtures.Q_SINGLE);
            assertEquals(VisibleQuestion.GRANT_TYPE_GRANTED, visible.grantType(),
                    "失效手段只剩显式撤销与学籍状态 —— 时间不再是其中之一");
            assertFalse(visible.owned());
        });
    }

    /**
     * <b>本文件最要紧的一条。</b>
     *
     * <p>授权粒度是父题，{@code org_resource_grant} 里没有子题的行。
     * 漏掉「子题折算到父题」这一步<b>在自有场景下看不出来</b>（父子同 owner），
     * 只有被授权场景才暴露：被授权方能看见父题、拿子题 ID 查详情却 404 ——
     * 接口不报错，只是少了一半数据。
     */
    @Test
    @DisplayName("材料题以父题为授权粒度：授权父题即连带其全部子题（03-04 §0.1）")
    void grantedParentMakesChildVisible() throws Exception {
        questionFixtures.grantQuestion(QuestionFixtures.Q_MATERIAL, QuestionFixtures.TB,
                QuestionFixtures.TENANT_ID);
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.TB, () -> {
            assertEquals(VisibleQuestion.GRANT_TYPE_GRANTED,
                    visibilityChecker.assertVisible(QuestionFixtures.Q_MATERIAL).grantType());
            VisibleQuestion child = visibilityChecker.assertVisible(QuestionFixtures.Q_CHILD_1);
            assertEquals(VisibleQuestion.GRANT_TYPE_GRANTED, child.grantType(),
                    "授权了父题却看不到子题 —— 被授权方拿到的是半份材料题，而接口 200");
            assertTrue(child.isChild());
            assertEquals(QuestionFixtures.Q_MATERIAL, child.parentId());
        });
    }

    @Test
    @DisplayName("未授权父题时子题同样不可见 → 404")
    void ungrantedParentHidesChild() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.TB, () ->
                assertThrows(BizException.class,
                        () -> visibilityChecker.assertVisible(QuestionFixtures.Q_CHILD_1)));
    }

    // ================================================================ visibleIds

    @Test
    @DisplayName("visibleIds = 自有 ∪ 被授权，且【只含父题与普通题】")
    void visibleIdsIsUnionOfOwnedAndGranted() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            questionFixtures.grantQuestion(QuestionFixtures.Q_TA, QuestionFixtures.TB,
                    QuestionFixtures.TENANT_ID);
            List<Long> ids = visibilityChecker.visibleIds(QuestionFixtures.TB);
            assertEquals(List.of(QuestionFixtures.Q_TA), ids);

            List<Long> rootIds = visibilityChecker.visibleIds(QuestionFixtures.ROOT);
            assertTrue(rootIds.contains(QuestionFixtures.Q_SINGLE));
            assertTrue(rootIds.contains(QuestionFixtures.Q_MATERIAL));
            assertFalse(rootIds.contains(QuestionFixtures.Q_CHILD_1),
                    "子题不该单独出现在可见集合里 —— 授权粒度是父题");
            assertFalse(rootIds.contains(QuestionFixtures.Q_TA),
                    "ROOT 看不到下级教师自建的题");
        });
    }

    // ================================================================ 版本快照

    @Test
    @DisplayName("snapshot 返回当前版本号；题目不存在 / 跨租户 → null")
    void snapshotReturnsCurrentVersion() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            assertEquals(1, versionService.snapshot(QuestionFixtures.Q_SINGLE));
            assertNull(versionService.snapshot(9999999999999999L));
            assertNull(versionService.snapshot(QuestionFixtures.Q_OTHER),
                    "跨租户题目的版本号不该被读到 —— 租户条件由插件注入");
        });
    }

    @Test
    @DisplayName("批量 snapshot：缺失的键 = 该题不可用，不抛异常")
    void batchSnapshotSkipsMissing() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            var versions = versionService.snapshot(
                    List.of(QuestionFixtures.Q_SINGLE, 9999999999999999L));
            assertEquals(1, versions.size());
            assertEquals(1, versions.get(QuestionFixtures.Q_SINGLE));
        });
    }

    @Test
    @DisplayName("read(id, version) 读不可变快照；版本不存在 → 30007")
    void readSnapshotOr30007() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            QuestionVersionService.QuestionSnapshot snapshot =
                    versionService.read(QuestionFixtures.Q_SINGLE, 1);
            assertEquals("A", snapshot.correctAnswer().path("answer").asText());
            BizException error = assertThrows(BizException.class,
                    () -> versionService.read(QuestionFixtures.Q_SINGLE, 99));
            assertEquals(ErrorCode.QUESTION_VERSION_NOT_FOUND.getCode(),
                    error.getErrorCode().getCode());
        });
    }

    // ================================================================ 归属提供方

    @Test
    @DisplayName("QUESTION 的归属提供方已注册 —— 在它之前 ResourceOwnerChecker 对 QUESTION 抛异常")
    void questionOwnerProviderIsRegistered() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            assertTrue(resourceOwnerChecker.registeredTypes().contains(ResourceType.QUESTION));
            assertEquals(QuestionFixtures.ROOT,
                    resourceOwnerChecker.ownerNodeIdOf(ResourceType.QUESTION, QuestionFixtures.Q_SINGLE));
            assertTrue(resourceOwnerChecker.isOwner(ResourceType.QUESTION,
                    QuestionFixtures.Q_SINGLE, QuestionFixtures.ROOT));
            assertFalse(resourceOwnerChecker.isOwner(ResourceType.QUESTION,
                    QuestionFixtures.Q_SINGLE, QuestionFixtures.TB));
        });
    }

    @Test
    @DisplayName("材料题子题的【归属】与父题相同，但【授权】只挂父题 —— 两个谓词不可合并")
    void childOwnerEqualsParentButGrantDoesNot() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            assertEquals(QuestionFixtures.ROOT,
                    resourceOwnerChecker.ownerNodeIdOf(ResourceType.QUESTION, QuestionFixtures.Q_CHILD_1));
            questionFixtures.grantQuestion(QuestionFixtures.Q_MATERIAL, QuestionFixtures.TB,
                    QuestionFixtures.TENANT_ID);
            assertTrue(resourceOwnerChecker.canUse(ResourceType.QUESTION,
                    QuestionFixtures.Q_MATERIAL, QuestionFixtures.TB));
            assertFalse(resourceOwnerChecker.canUse(ResourceType.QUESTION,
                    QuestionFixtures.Q_CHILD_1, QuestionFixtures.TB),
                    "ResourceOwnerChecker 不做父子折算 —— 折算在 QuestionVisibilityProvider，"
                            + "两处口径不同是刻意的：前者回答「这一行的归属/授权」，后者回答「我能不能看到它」");
        });
    }
}
