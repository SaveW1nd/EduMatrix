package com.edumatrix.system.role.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.CurrentContextProvider;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.system.role.entity.SysRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PresetRoleGuard} 判定矩阵的穷举（03-01 §3.4 / §3.5 / §3.6）。
 *
 * <p>不进容器：判定只取决于「目标是不是预置角色」与「调用者是不是超管」两个布尔，
 * 四种组合一个不落地列在下面。集成测试验的是<b>接口层面真的被拒了</b>，
 * 这里验的是<b>矩阵没有缺角</b> —— 两者都要有：只有 IT 时，
 * 加一个新的角色写接口而忘了调 Guard，IT 不会红。
 *
 * <h2>为什么可以安全地改 {@code TenantHelper} 的静态 provider</h2>
 * <p>它是全局静态字段，而集成测试（{@code *IT}，failsafe）与单元测试
 * （{@code *Test}，surefire）跑在<b>不同的 JVM</b> 里，互不影响。
 * 即便如此，下面仍然在 {@code @AfterEach} 里复位 —— 静态状态泄漏出去的表现是
 * 「另一个测试莫名其妙地以超管身份跑」，那种失败没人查得动。
 */
class PresetRoleGuardTest {

    private final PresetRoleGuard guard = new PresetRoleGuard();

    @AfterEach
    void resetProvider() {
        TenantHelper.setProvider(null);
        TenantHelper.reset();
    }

    @Nested
    @DisplayName("§3.4 / §3.6 修改与分配菜单")
    class Writable {

        @Test
        @DisplayName("org_admin × 预置角色 → 400（预置角色对机构管理员全只读）")
        void orgAdminCannotWritePresetRole() {
            asOrgAdmin();

            assertThatThrownBy(() -> guard.assertWritable(presetRole()))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("org_admin × 自建角色 → 放行")
        void orgAdminCanWriteOwnRole() {
            asOrgAdmin();

            assertThatCode(() -> guard.assertWritable(customRole())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("super_admin × 预置角色 → 放行（改预置角色属平台级操作）")
        void superAdminCanWritePresetRole() {
            asSuperAdmin();

            assertThatCode(() -> guard.assertWritable(presetRole())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("super_admin × 自建角色 → 放行")
        void superAdminCanWriteCustomRole() {
            asSuperAdmin();

            assertThatCode(() -> guard.assertWritable(customRole())).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("§3.5 删除 —— 预置角色任何人不可删")
    class Deletable {

        @Test
        @DisplayName("org_admin × 预置角色 → 400")
        void orgAdminCannotDeletePresetRole() {
            asOrgAdmin();

            assertThatThrownBy(() -> guard.assertDeletable(presetRole()))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("super_admin × 预置角色 → 400（这一格是本矩阵与 §3.4 唯一不同的地方）")
        void superAdminCannotDeletePresetRoleEither() {
            asSuperAdmin();

            // §3.5 原文：「预置角色任何人都不可删除（含 super_admin），返回 400 ——
            // 四个角色是契约第 3 节的固定集合，删掉即全平台该类用户失权」。
            // 所以 assertDeletable 里【没有超管分支】，那不是漏写
            assertThatThrownBy(() -> guard.assertDeletable(presetRole()))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("任何人 × 自建角色 → 放行（引用检查由 10008 另行承担）")
        void customRoleIsDeletable() {
            asOrgAdmin();
            assertThatCode(() -> guard.assertDeletable(customRole())).doesNotThrowAnyException();

            asSuperAdmin();
            assertThatCode(() -> guard.assertDeletable(customRole())).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("§3.3 roleKey 不得占用四个预置值")
    class RoleKey {

        @Test
        @DisplayName("四个预置标识逐个被拒")
        void presetRoleKeysAreRejected() {
            for (String key : new String[]{"super_admin", "org_admin", "teacher", "student"}) {
                assertThatThrownBy(() -> guard.assertRoleKeyNotPreset(key))
                        .as("roleKey=%s 必须被拒", key)
                        .isInstanceOf(BizException.class);
            }
        }

        @Test
        @DisplayName("首尾空白不能绕过（trim 后再比）")
        void whitespaceDoesNotBypass() {
            assertThatThrownBy(() -> guard.assertRoleKeyNotPreset("  teacher  "))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("自定义标识放行")
        void customRoleKeyIsAccepted() {
            assertThatCode(() -> guard.assertRoleKeyNotPreset("academic_director"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("预置的判据是 tenant_id = 0，不是 role_key 白名单")
    void presetIsDecidedByTenantIdNotRoleKey() {
        // 一个 role_key 长得像自建、但 tenant_id = 0 的行，仍然是预置角色。
        // 按 role_key 白名单判会把它当成可改的自建角色 —— 而它是全平台共用的那一行
        SysRole platformRow = new SysRole();
        platformRow.setRoleKey("some_new_platform_role");
        platformRow.setTenantId(SysRole.PLATFORM_TENANT_ID);
        assertThat(platformRow.isPreset()).isTrue();

        // 反过来：租户自建的行即便 role_key 眼熟，也不是预置角色（它不该存在，
        // §3.3 的 assertRoleKeyNotPreset 会先拦住；但真出现了，判据仍是 tenant_id）
        SysRole tenantRow = new SysRole();
        tenantRow.setRoleKey("teacher");
        tenantRow.setTenantId(1960000000000000001L);
        assertThat(tenantRow.isPreset()).isFalse();
    }

    // =====================================================================

    private static SysRole presetRole() {
        SysRole role = new SysRole();
        role.setId(1953827104412590203L);
        role.setRoleKey("teacher");
        role.setTenantId(SysRole.PLATFORM_TENANT_ID);
        return role;
    }

    private static SysRole customRole() {
        SysRole role = new SysRole();
        role.setId(1961000000000000001L);
        role.setRoleKey("academic_director");
        role.setTenantId(1960000000000000001L);
        return role;
    }

    private static void asOrgAdmin() {
        TenantHelper.setProvider(new StubContext(1960000000000000001L, false));
    }

    private static void asSuperAdmin() {
        TenantHelper.setProvider(new StubContext(null, true));
    }

    /** 最小会话桩。不用测试目录里那个 {@code TestCurrentContextProvider} —— 它是模块 01 的构件。 */
    private record StubContext(Long tenantId, boolean superAdmin) implements CurrentContextProvider {

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
            return 1960000000000000111L;
        }

        @Override
        public Long getNodeId() {
            return 1960000000000000010L;
        }
    }
}
