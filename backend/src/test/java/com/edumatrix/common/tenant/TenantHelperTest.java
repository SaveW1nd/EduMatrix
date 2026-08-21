package com.edumatrix.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantHelper} 的取值优先级与兜底行为（契约 §2.8）。
 */
class TenantHelperTest {

    @AfterEach
    void tearDown() {
        TenantHelper.reset();
        TenantHelper.setProvider(new NoSessionCurrentContextProvider());
    }

    private static CurrentContextProvider session(Long tenantId, boolean superAdmin) {
        return new CurrentContextProvider() {
            @Override
            public Long getTenantId() {
                return tenantId;
            }

            @Override
            public boolean isSuperAdmin() {
                return superAdmin;
            }

            @Override
            public Long getUserId() {
                return 100L;
            }

            @Override
            public Long getNodeId() {
                return 200L;
            }

    @Override
    public Integer getUserType() {
        return null;
    }
        };
    }

    @Nested
    @DisplayName("取值优先级")
    class Priority {

        @Test
        @DisplayName("① 显式 runWithTenant 压过 ④ 会话")
        void explicitBeatsSession() {
            TenantHelper.setProvider(session(9L, false));
            assertThat(TenantHelper.getTenantIdOrNull()).isEqualTo(9L);

            TenantHelper.runWithTenant(5L, () ->
                    assertThat(TenantHelper.getTenantIdOrNull())
                            .as("线程池会复用线程，显式设置必须压过一切隐式来源")
                            .isEqualTo(5L));
        }

        @Test
        @DisplayName("③ 超管整体放行，但显式租户上下文优先于超管身份")
        void explicitBeatsSuperAdmin() {
            TenantHelper.setProvider(session(null, true));
            assertThat(TenantHelper.isSuperAdminSession()).isTrue();

            TenantHelper.runWithTenant(5L, () ->
                    assertThat(TenantHelper.isSuperAdminSession())
                            .as("超管手动指定操作哪个租户时，应当按那个租户过滤，而不是继续全局放行")
                            .isFalse());
        }

        @Test
        @DisplayName("④ 普通请求走会话")
        void sessionIsFallback() {
            TenantHelper.setProvider(session(9L, false));
            assertThat(TenantHelper.requireTenantId()).isEqualTo(9L);
        }
    }

    @Test
    @DisplayName("兜底：四条路径全落空 → 抛异常，绝不放行")
    void missingContextThrows() {
        assertThatThrownBy(TenantHelper::requireTenantId)
                .isInstanceOf(TenantContextMissingException.class)
                .hasMessageContaining("runWithTenant");
        assertThat(TenantHelper.getTenantIdOrNull()).isNull();
    }

    @Test
    @DisplayName("runWithTenant 结束后恢复外层上下文，而不是清空")
    void runWithTenantRestoresOuterContext() {
        TenantHelper.runWithTenant(1L, () -> {
            TenantHelper.runWithTenant(2L, () ->
                    assertThat(TenantHelper.getExplicitTenantId()).isEqualTo(2L));
            assertThat(TenantHelper.getExplicitTenantId())
                    .as("嵌套调用不能把外层上下文抹掉")
                    .isEqualTo(1L);
        });
        assertThat(TenantHelper.getExplicitTenantId()).isNull();
    }

    @Test
    @DisplayName("业务抛异常时 clear() 照样执行")
    void clearRunsOnException() {
        assertThatThrownBy(() -> TenantHelper.runWithTenant(1L, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantHelper.getExplicitTenantId())
                .as("漏 clear() 就是线程池复用时下一个任务串租户")
                .isNull();
    }

    @Test
    @DisplayName("runWithTenant(null) 直接拒绝，不猜一个")
    void nullTenantIsRejected() {
        assertThatThrownBy(() -> TenantHelper.runWithTenant(null, () -> {
        })).isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    @DisplayName("ignore() 可嵌套，内层退出不会提前关掉外层")
    void ignoreIsReentrant() {
        assertThat(TenantHelper.isIgnored()).isFalse();
        TenantHelper.ignore(() -> {
            assertThat(TenantHelper.isIgnored()).isTrue();
            TenantHelper.ignore(() -> assertThat(TenantHelper.isIgnored()).isTrue());
            assertThat(TenantHelper.isIgnored())
                    .as("用计数而非布尔，内层 ignore 退出不能把外层一起关掉")
                    .isTrue();
        });
        assertThat(TenantHelper.isIgnored()).isFalse();
    }

    @Test
    @DisplayName("ignore() 抛异常时也要复位")
    void ignoreResetsOnException() {
        assertThatThrownBy(() -> TenantHelper.ignore(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(TenantHelper.isIgnored()).isFalse();
    }
}
