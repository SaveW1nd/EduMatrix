package com.edumatrix.auth.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code /auth/me} 的 {@code roles} / {@code perms} 加载链路
 * （{@code sys_user_role → sys_role → sys_role_menu → sys_menu}，04 §B 规则 11）。
 *
 * <h2>这条链路上有一个「不报错的故障」</h2>
 * <p>四个内置角色及其菜单绑定的 {@code tenant_id = 0}。若 {@code sys_role} /
 * {@code sys_role_menu} 的租户插件注入条件是普通等式而不是
 * {@code (tenant_id = ? OR tenant_id = 0)}，<b>非超管用户一律返回
 * {@code roles: []} / {@code perms: []}</b> —— 接口 200、字段齐全、只是数组为空
 * （03-01 §1.5 / 契约 §2.9）。
 *
 * <p><b>那个放行只写在一处</b>：{@code common/tenant/PlatformRowTenantLineInnerInterceptor}。
 * 本 Mapper 里<b>绝不出现手写的 {@code OR tenant_id = 0}</b> ——
 * {@code scripts/check_backend_conventions.sh} 的检查①正是 grep 它。
 * 查出来是 0 行时该去看那个拦截器，不是在这里 workaround。
 *
 * <h2>为什么不过滤 {@code status}</h2>
 * <p>{@code sys_role.status} 与 {@code sys_menu.status} 都有「1 停用」，但
 * <b>「停用的角色/菜单是否仍授予 perms」全套文档没有定义</b>。04 §B 规则 11 描述的链路
 * 就是这四张表的直连，本模块照字面实现，不擅自加条件 —— 加了就是发明一条判定规则，
 * 而且会让实测计数与 F-1 定案的基线数字对不上，届时无从分辨是数据问题还是代码问题。
 */
@Mapper
public interface AuthPermMapper {

    /**
     * 用户的全部角色（03-01 §1.5：{@code roleKey} 对齐契约 §3 的四个值）。
     *
     * <p><b>响应里不含 {@code dataScope}</b> —— 数据范围由 {@code nodeId} 的子树决定，
     * 不在角色上配置（03-01 §1.5 末尾明写）。
     */
    @Select("SELECT r.id AS roleId, r.role_name AS roleName, r.role_key AS roleKey "
            + "FROM sys_user_role ur "
            + "JOIN sys_role r ON r.id = ur.role_id AND r.deleted_at = 0 "
            + "WHERE ur.user_id = #{userId} AND ur.deleted_at = 0 "
            + "ORDER BY r.sort, r.id")
    List<RoleRow> selectRoles(@Param("userId") Long userId);

    /**
     * 用户的按钮/功能权限标识（{@code sys_menu.perms}，前端据此控制按钮显隐）。
     *
     * <p>{@code perms} 可空（7 行 {@code M} 目录没有标识），故 {@code IS NOT NULL};
     * {@code DISTINCT} 是因为一个 {@code perms} 可能经多个角色到达同一用户。
     *
     * <p><b>学生返回空数组是设计意图，不是故障</b>：F-1 定案②「{@code student}
     * 不绑任何菜单行 —— 学生端接口一律不加 {@code @SaCheckPermission}，
     * 只按角色与数据权限判定」。区分方法：{@code roles} 里查得到 {@code student} 这一行，
     * 说明 {@code tenant_id = 0} 的平台级放行是生效的。
     */
    @Select("SELECT DISTINCT m.perms "
            + "FROM sys_user_role ur "
            + "JOIN sys_role_menu rm ON rm.role_id = ur.role_id AND rm.deleted_at = 0 "
            + "JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted_at = 0 "
            + "WHERE ur.user_id = #{userId} AND ur.deleted_at = 0 AND m.perms IS NOT NULL "
            + "ORDER BY m.perms")
    List<String> selectPerms(@Param("userId") Long userId);

    /** {@code sys_role} 的窄投影，直接对应 03-01 §1.5 的 {@code roles[]} 元素。 */
    class RoleRow {
        private Long roleId;
        private String roleName;
        private String roleKey;

        public Long getRoleId() {
            return roleId;
        }

        public void setRoleId(Long roleId) {
            this.roleId = roleId;
        }

        public String getRoleName() {
            return roleName;
        }

        public void setRoleName(String roleName) {
            this.roleName = roleName;
        }

        public String getRoleKey() {
            return roleKey;
        }

        public void setRoleKey(String roleKey) {
            this.roleKey = roleKey;
        }
    }
}
