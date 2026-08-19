package com.edumatrix.common.resource;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.grant.mapper.ResourceGrantMapper;

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

    @Test
    @DisplayName("契约 §2.5 规则 8：isOwner 是严格相等，被授权者不算 owner")
    void isOwnerIsStrict() {
        ResourceOwnerChecker checker = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of(1L, 100L))), grantReader(true));
        assertTrue(checker.isOwner(ResourceType.COURSE, 1L, 100L));
        assertFalse(checker.isOwner(ResourceType.COURSE, 1L, 200L),
                "被授权者被判成了 owner —— 编排权限就此失守");
    }

    @Test
    @DisplayName("契约 §2.5 规则 1：canUse 是 owner ∪ 被授权，与 isOwner 不是同一个谓词")
    void canUseIsUnion() {
        ResourceOwnerChecker granted = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of(1L, 100L))), grantReader(true));
        assertTrue(granted.canUse(ResourceType.COURSE, 1L, 200L));

        ResourceOwnerChecker notGranted = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of(1L, 100L))), grantReader(false));
        assertFalse(notGranted.canUse(ResourceType.COURSE, 1L, 200L));
        assertTrue(notGranted.canUse(ResourceType.COURSE, 1L, 100L), "owner 自己必须可用");
    }

    @Test
    @DisplayName("未注册的资源类型响亮失败，绝不静默返回 false")
    void unregisteredTypeFailsLoudly() {
        ResourceOwnerChecker checker = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of())), grantReader(false));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> checker.isOwner(ResourceType.VIDEO, 1L, 100L));
        assertTrue(error.getMessage().contains("模块 09"),
                "错误信息里要写清谁来补，否则下一个人只知道炸了：" + error.getMessage());
    }

    @Test
    @DisplayName("同一类型注册两个实现 —— 启动即失败，不留到运行期按注入顺序抽签")
    void duplicateProviderFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of()),
                        provider(ResourceType.COURSE, Map.of())),
                grantReader(false)));
    }

    @Test
    @DisplayName("模块 08 只注册 COURSE —— 这个集合被钉住，模块 09 / 10 补齐时会提醒改这条")
    void registeredTypesAreCourseOnly() {
        ResourceOwnerChecker checker = new ResourceOwnerChecker(
                List.of(provider(ResourceType.COURSE, Map.of())), grantReader(false));
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
