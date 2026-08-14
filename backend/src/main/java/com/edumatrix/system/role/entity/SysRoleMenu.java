package com.edumatrix.system.role.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code sys_role_menu} 角色-菜单关联表（03-01 §3.6）。
 *
 * <p>与 {@code sys_role} 一样是<b>契约 §2.9 的放行表</b>：租户插件对它注入
 * {@code (tenant_id = ? OR tenant_id = 0)}，否则非超管用户装配 {@code perms} 时命中 0 行、
 * 所有权限校验 403。放行只写在
 * {@code common/tenant/PlatformRowTenantLineInnerInterceptor} 一处，
 * 本类与它的 Mapper 里<b>一个字都不写</b>。
 *
 * <p>{@code uk_role_menu(role_id, menu_id, deleted_at)} <b>不含 {@code tenant_id}</b>，
 * DDL 注释解释过：{@code role_id} 是雪花 ID、全局唯一，一个角色只属于一个租户，
 * 加上 {@code tenant_id} 反而会让「同一角色在不同 tenant_id 下重复绑定同一菜单」成为合法。
 */
@TableName("sys_role_menu")
public class SysRoleMenu extends TenantEntity {

    private static final long serialVersionUID = 1L;

    private Long roleId;

    private Long menuId;

    public SysRoleMenu() {
    }

    public SysRoleMenu(Long roleId, Long menuId) {
        this.roleId = roleId;
        this.menuId = menuId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public Long getMenuId() {
        return menuId;
    }

    public void setMenuId(Long menuId) {
        this.menuId = menuId;
    }
}
