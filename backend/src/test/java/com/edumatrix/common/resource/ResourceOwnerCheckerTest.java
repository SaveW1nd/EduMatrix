package com.edumatrix.common.resource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.grant.mapper.ResourceGrantMapper;
import com.edumatrix.common.subtree.NodeAncestorCache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceOwnerChecker}：E 定案的两个谓词，以及「未注册的类型必须响亮失败」。
 *
 * <h2>为什么单独测「抛异常」</h2>
 * <p>模块 08 只注册 {@link ResourceType#COURSE}。若未注册的类型返回 {@code false}，
 * 模块 11 的授权引擎会静默判定「你不是 owner」—— <b>接口 200、字段齐全、结果错</b>，
 * 正是本项目 1 号失败模式。把 {@code throw} 改回 {@code return false}，
 * {@link #unregisteredTypeFailsLoudly} 立刻红。
 */
class ResourceOwnerCheckerTest {

    /** 只回答归属，不查库。 */
    private static ResourceOwnerProvider provider(ResourceType type, Map<Long, Long> owners) {
        return new ResourceOwnerProvider() {
            @Override
            public ResourceType resourceType() {
                return type;
            }

            @Override
            public Long ownerNodeIdOf(Long resourceId) {
                return owners.get(resourceId);
            }
        };
    }

    /** 只回答「有没有授权」，不查库。 */
    private static ResourceGrantReader grantReader(boolean granted) {
        return new ResourceGrantReader(null) {
            @Override
            public boolean hasGrant(ResourceType type, Long resourceId, Long nodeId) {
                return granted;
            }
        };
    }

    /** 没有祖先链（机构根 / 老用例用）—— canRegrant 之外的用例都不关心它。 */
    private static NodeAncestorCache noAncestors() {
        return ancestors(Map.of());
    }

    /** {@code nodeId → ancestors 串}（根在前，形如 {@code "0,100,101"}）。 */
    private static NodeAncestorCache ancestors(Map<Long, String> chains) {
        return new NodeAncestorCache(null, null) {
            @Override
            public String get(Long nodeId) {
                return chains.get(nodeId);
            }
        };
    }

    /** 指定「哪些节点持有该资源」的读侧桩（{@code hasGrant} 与 {@code grantHolders} 同源）。 */
    private static ResourceGrantReader holders(Set<Long> holderNodeIds) {
        return new ResourceGrantReader(null) {
            @Override
            public boolean hasGrant(ResourceType type, Long resourceId, Long nodeId) {
                return holderNodeIds.contains(nodeId);
            }

            @Override
            public Map<Long, Set<Long>> grantHolders(ResourceType type,
                                                     java.util.Collection<Long> resourceIds,
                                                     java.util.Collection<Long> candidateNodeIds) {
                Set<Long> hit = new java.util.LinkedHashSet<>(candidateNodeIds);
                hit.retainAll(holderNodeIds);
                Map<Long, Set<Long>> result = new java.util.LinkedHashMap<>();
                resourceIds.forEach(id -> result.put(id, hit));
                return result;
            }
        };
    }

    @Test
    @DisplayName("契约 §2.5 规则 8：isOwner 是严格相等，被授权者不算 owner")
    void isOwnerIsStrict() {
        ResourceOwnerChecker checker = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of(1L, 100L))), grantReader(true), noAncestors());
        assertTrue(checker.isOwner(ResourceType.COURSE, 1L, 100L));
        assertFalse(checker.isOwner(ResourceType.COURSE, 1L, 200L),
                "被授权者被判成了 owner —— 编排权限就此失守");
    }

    @Test
    @DisplayName("契约 §2.5 规则 1：canUse 是 owner ∪ 被授权，与 isOwner 不是同一个谓词")
    void canUseIsUnion() {
        ResourceOwnerChecker granted = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of(1L, 100L))), grantReader(true), noAncestors());
        assertTrue(granted.canUse(ResourceType.COURSE, 1L, 200L));

        ResourceOwnerChecker notGranted = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of(1L, 100L))), grantReader(false), noAncestors());
        assertFalse(notGranted.canUse(ResourceType.COURSE, 1L, 200L));
        assertTrue(notGranted.canUse(ResourceType.COURSE, 1L, 100L), "owner 自己必须可用");
    }

    // =====================================================================
    // canRegrant —— 契约 §2.5 规则 9「跨管辖降级只读」（F-81）
    //
    // 树形（根在前）：ROOT(1) → A(2) → T(3)，资源 R=7 的 owner 见各用例
    // =====================================================================

    private static final long ROOT = 1L;
    private static final long A = 2L;
    private static final long T = 3L;
    private static final long B = 4L;
    private static final long RESOURCE = 7L;

    /** T 挂在 ROOT→A 下。祖先链串里首位 0 是平台根哨兵，parseAncestorIds 会跳过。 */
    private static ResourceOwnerChecker checkerFor(long ownerNodeId, Set<Long> holderNodeIds) {
        return new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of(RESOURCE, ownerNodeId))),
                holders(holderNodeIds),
                ancestors(Map.of(T, "0," + ROOT + "," + A,
                        A, "0," + ROOT,
                        B, "0," + ROOT)));
    }

    @Test
    @DisplayName("自有资源永久可再下发 —— 不看链")
    void ownerCanAlwaysRegrant() {
        assertTrue(checkerFor(T, Set.of()).canRegrant(ResourceType.COURSE, RESOURCE, T));
    }

    @Test
    @DisplayName("链完整（owner=ROOT，A 持有）→ T 可再下发")
    void intactChainAllowsRegrant() {
        ResourceOwnerChecker checker = checkerFor(ROOT, Set.of(A, T));
        assertTrue(checker.canRegrant(ResourceType.COURSE, RESOURCE, T));
        assertTrue(checker.canUse(ResourceType.COURSE, RESOURCE, T), "能用是前提，两者此处同为真");
    }

    @Test
    @DisplayName("⚠ 跨管辖：owner=B（不在 T 的祖先链上）且链上无人持有 → 能用、【不能再下发】")
    void crossScopeGrantIsReadOnly() {
        ResourceOwnerChecker checker = checkerFor(B, Set.of(T));
        assertTrue(checker.canUse(ResourceType.COURSE, RESOURCE, T),
                "契约 §2.5 规则 9：跨管辖授权【仍可使用】，学员不能因为导师调岗就断课");
        assertFalse(checker.canRegrant(ResourceType.COURSE, RESOURCE, T),
                "丧失再下发能力。判假为真 = 调岗的教师能把原校区的课授给新校区学员，"
                        + "而那【不会报错】——契约 §2.5 规则 9 逐字描述的资产穿透");
    }

    @Test
    @DisplayName("⚠ 链在中间断了（owner=ROOT，A 已被撤销）→ T 只读，即使 ROOT 仍是 owner")
    void brokenMiddleLinkIsReadOnly() {
        assertFalse(checkerFor(ROOT, Set.of(T)).canRegrant(ResourceType.COURSE, RESOURCE, T),
                "「祖先链上存在任意持有者」的读法会在这里判真 —— 而 ROOT 是 owner、A 无权、"
                        + "T 有权，正是契约 §2.5 规则 6 的【真悬挂】。PRD FR-2 规则 1 逐字："
                        + "这种行「学生仍能学但导师无法管理」，本来就该只读");
    }

    @Test
    @DisplayName("我自己没有授权 → 不可再下发（canUse 也为假）")
    void noGrantNoRegrant() {
        ResourceOwnerChecker checker = checkerFor(ROOT, Set.of(A));
        assertFalse(checker.canRegrant(ResourceType.COURSE, RESOURCE, T));
        assertFalse(checker.canUse(ResourceType.COURSE, RESOURCE, T));
    }

    @Test
    @DisplayName("资源不存在 → 与「你无权」同一个出口，不可再下发（契约 §2.5 规则 1 防探测）")
    void missingResourceIsIndistinguishableFromNoRight() {
        ResourceOwnerChecker checker = checkerFor(ROOT, Set.of(A, T));
        assertTrue(checker.regrantableIds(ResourceType.COURSE, List.of(99L), T).isEmpty());
    }

    @Test
    @DisplayName("regrantableIds 批量结果 = 逐个 canRegrant（两者不得分叉）")
    void batchMatchesSingle() {
        ResourceOwnerChecker checker = checkerFor(B, Set.of(T));
        assertEquals(Set.of(), checker.regrantableIds(ResourceType.COURSE, List.of(RESOURCE), T));
        assertFalse(checker.canRegrant(ResourceType.COURSE, RESOURCE, T));

        ResourceOwnerChecker intact = checkerFor(ROOT, Set.of(A, T));
        assertEquals(Set.of(RESOURCE),
                intact.regrantableIds(ResourceType.COURSE, List.of(RESOURCE), T));
        assertTrue(intact.canRegrant(ResourceType.COURSE, RESOURCE, T));
    }

    @Test
    @DisplayName("未注册的资源类型响亮失败，绝不静默返回 false")
    void unregisteredTypeFailsLoudly() {
        ResourceOwnerChecker checker = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of())), grantReader(false), noAncestors());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> checker.isOwner(ResourceType.VIDEO, 1L, 100L));
        // ⚠ 本断言原先钉的是 contains("模块 09") —— 那时 VIDEO 还没注册。
        // 模块 09 补齐 VIDEO 之后那句话就过期了（三类受管资源现已全部注册），
        // 而【钉一个会过期的具体模块号】正是这条断言自己要防的形态：
        // 它要保证的是「错误信息说得清该干什么」，不是「错误信息里有『模块 09』四个字」。
        // 故改为钉住那句不随模块补齐而失效的处置指引。
        assertTrue(error.getMessage().contains("所属领域注册一个提供方"),
                "错误信息里要写清谁来补，否则下一个人只知道炸了：" + error.getMessage());
        assertTrue(error.getMessage().contains("响亮失败"),
                "要说清这是有意抛的，不是意外：" + error.getMessage());
    }

    @Test
    @DisplayName("同一类型注册两个实现 —— 启动即失败，不留到运行期按注入顺序抽签")
    void duplicateProviderFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of()),
                        provider(ResourceType.COURSE, Map.of())),
                grantReader(false), noAncestors()));
    }

    @Test
    @DisplayName("模块 08 只注册 COURSE —— 这个集合被钉住，模块 09 / 10 补齐时会提醒改这条")
    void registeredTypesAreCourseOnly() {
        ResourceOwnerChecker checker = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of())), grantReader(false), noAncestors());
        assertEquals(java.util.Set.of(ResourceType.COURSE), checker.registeredTypes());
    }

    @Test
    @DisplayName("resource_type 取值与契约 §5 / org_resource_grant DDL 注释一致")
    void resourceTypeCodes() {
        assertEquals(1, ResourceType.COURSE.code());
        assertEquals(2, ResourceType.QUESTION.code());
        assertEquals(3, ResourceType.VIDEO.code());
        assertEquals(ResourceType.COURSE, ResourceType.of(1).orElseThrow());
        assertTrue(ResourceType.of(9).isEmpty());
        assertTrue(ResourceType.of(null).isEmpty());
    }

    /** 让编译器确认窄 Mapper 的类型没写错（本测试不连库）。 */
    @SuppressWarnings("unused")
    private static ResourceGrantMapper unusedTypeAnchor;
}
