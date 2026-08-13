package com.edumatrix.common.subtree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestCurrentContextProvider;

/**
 * 数据权限选路表在<b>真实数据</b>上的行为（契约 §2.4 / 02-数据库设计 §3.1.2）。
 *
 * <p>用基线自带的示例树：
 * <pre>
 * 平台根 0（node_type=0, tenant_id=0, ancestors=''）
 *   └─ 机构管理员 ...590001（1, ancestors='0'，id = tenant_id）
 *        └─ 教师 ...590403（2, ancestors='0,...590001'）
 *             └─ 学生 ...590404（3, ancestors='0,...590001,...590403'）
 * </pre>
 */
@IntegrationTest
class SubtreeScopeHelperIT {

    private static final long ORG_ADMIN_NODE = 1953827104412590001L;
    private static final long TEACHER_NODE = 1953827104412590403L;
    private static final long STUDENT_NODE = 1953827104412590404L;
    private static final long TENANT_A = ORG_ADMIN_NODE;

    @Autowired
    private SubtreeScopeHelper subtreeScopeHelper;

    @Autowired
    private NodeAncestorCache ancestorCache;

    @Autowired
    private TestCurrentContextProvider context;

    @AfterEach
    void tearDown() {
        context.asNoSession();
        TenantHelper.reset();
    }

    private void loginAsTenantAdmin() {
        context.asTenantUser(TENANT_A, 1953827104412590102L, ORG_ADMIN_NODE);
    }

    @Test
    @DisplayName("管理员：前缀 LIKE 取整棵子树（含自身），命中 idx_ancestors")
    void adminTakesWholeSubtreeByPrefix() {
        loginAsTenantAdmin();

        assertThat(subtreeScopeHelper.subtreeNodeIds(ORG_ADMIN_NODE))
                .as("子树 = 自身 + 全部后代")
                .containsExactlyInAnyOrder(ORG_ADMIN_NODE, TEACHER_NODE, STUDENT_NODE);
    }

    @Test
    @DisplayName("教师：退化为 parent_id 直查（最高频路径），子树 ≡ 自身 + 直接子节点")
    void teacherDegradesToDirectChildren() {
        loginAsTenantAdmin();

        assertThat(subtreeScopeHelper.subtreeNodeIds(TEACHER_NODE))
                .as("教师节点下只能挂学生，所以子树恰好等于直接子节点")
                .containsExactlyInAnyOrder(TEACHER_NODE, STUDENT_NODE);
    }

    @Test
    @DisplayName("学生：叶子，子树只有自己")
    void studentSeesOnlySelf() {
        loginAsTenantAdmin();

        assertThat(subtreeScopeHelper.subtreeNodeIds(STUDENT_NODE))
                .containsExactly(STUDENT_NODE);
    }

    @Test
    @DisplayName("逐层浏览走 parent_id，不取整棵子树")
    void childrenAreLoadedLevelByLevel() {
        loginAsTenantAdmin();

        assertThat(subtreeScopeHelper.childNodeIds(ORG_ADMIN_NODE)).containsExactly(TEACHER_NODE);
        assertThat(subtreeScopeHelper.childNodeIds(TEACHER_NODE)).containsExactly(STUDENT_NODE);
        assertThat(subtreeScopeHelper.childNodeIds(STUDENT_NODE)).isEmpty();
    }

    @Test
    @DisplayName("子树判定：向下可见、向上不可见")
    void subtreeJudgementIsOneDirectional() {
        loginAsTenantAdmin();

        assertThat(subtreeScopeHelper.isInSubtree(ORG_ADMIN_NODE, STUDENT_NODE)).isTrue();
        assertThat(subtreeScopeHelper.isInSubtree(TEACHER_NODE, STUDENT_NODE)).isTrue();
        assertThat(subtreeScopeHelper.isInSubtree(ORG_ADMIN_NODE, ORG_ADMIN_NODE)).isTrue();

        assertThat(subtreeScopeHelper.isInSubtree(STUDENT_NODE, TEACHER_NODE))
                .as("学生看不到自己的导师 —— 数据范围只由树决定，方向是自上而下")
                .isFalse();
        assertThat(subtreeScopeHelper.isInSubtree(TEACHER_NODE, ORG_ADMIN_NODE)).isFalse();
    }

    @Test
    @DisplayName("越界三分法：路径上的资源 → 404；请求体里选的目标 → 10107")
    void outOfScopeFollowsThreeWaySplit() {
        loginAsTenantAdmin();

        assertThatThrownBy(() -> subtreeScopeHelper.assertInSubtree(STUDENT_NODE, TEACHER_NODE))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .as("不暴露存在性，与跨租户一致")
                        .isEqualTo(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> subtreeScopeHelper.assertTargetInSubtree(STUDENT_NODE, TEACHER_NODE))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .as("用户主动选了越界对象，要明确提示「请重新选择」，而非静默 404")
                        .isEqualTo(ErrorCode.TARGET_NODE_OUT_OF_SCOPE));

        // 批量：整批拒绝并列出越界的那些
        assertThatThrownBy(() -> subtreeScopeHelper.assertTargetsInSubtree(
                TEACHER_NODE, java.util.List.of(STUDENT_NODE, ORG_ADMIN_NODE)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TARGET_NODE_OUT_OF_SCOPE);
                    assertThat(ex.getData()).isEqualTo(java.util.List.of(ORG_ADMIN_NODE));
                });
    }

    @Test
    @DisplayName("跨租户的节点在子树判定里一律不存在（租户插件已经把它过滤掉了）")
    void crossTenantNodeIsInvisible() {
        context.asTenantUser(999L, 999L, 999L);

        assertThat(subtreeScopeHelper.isInSubtree(999L, STUDENT_NODE))
                .as("租户隔离是硬边界，与子树权限是两道独立防线")
                .isFalse();
        assertThat(subtreeScopeHelper.subtreeNodeIds(999L))
                .as("节点查不到时返回空集，调用方必须当作「什么都看不到」")
                .isEmpty();
    }

    @Test
    @DisplayName("祖先链缓存：读得到、可失效；解析时跳过首位哨兵 0")
    void ancestorCacheRoundTrip() {
        loginAsTenantAdmin();

        String ancestors = ancestorCache.get(STUDENT_NODE);
        assertThat(ancestors).isEqualTo("0," + ORG_ADMIN_NODE + "," + TEACHER_NODE);

        assertThat(NodePath.parseAncestorIds(ancestors))
                .as("首位 0 是平台根哨兵，不是可读节点 —— "
                        + "按 IN 查名称时返回行数比 id 数少 1 是正确行为")
                .containsExactly(ORG_ADMIN_NODE, TEACHER_NODE);

        // 第二次读走缓存，结果必须一致
        assertThat(ancestorCache.get(STUDENT_NODE)).isEqualTo(ancestors);

        ancestorCache.evictSubtree(ORG_ADMIN_NODE);
        assertThat(ancestorCache.get(STUDENT_NODE))
                .as("失效后回源查库，结果不变")
                .isEqualTo(ancestors);
    }

    @Test
    @DisplayName("平台根哨兵：租户会话下读不到它，且这不影响任何判定")
    void rootSentinelIsFilteredForTenantSession() {
        loginAsTenantAdmin();

        assertThat(ancestorCache.getPath(0L))
                .as("哨兵的 tenant_id = 0 且不在放行清单里，租户会话读不到它 —— "
                        + "契约 §2.9：不放行，且不需要放行")
                .isNull();

        // 而机构根节点自身的 ancestors 是 '0'，去掉哨兵后为空：
        // 面包屑口径是「自租户根到自身」，平台根出现在租户面包屑里反而是越界
        assertThat(NodePath.parseAncestorIds(ancestorCache.get(ORG_ADMIN_NODE))).isEmpty();
    }
}
