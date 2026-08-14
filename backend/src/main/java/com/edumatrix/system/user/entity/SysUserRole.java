package com.edumatrix.system.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code sys_user_role} 用户-角色关联表（03-01 §2.2 / §2.3 的 {@code roleIds}）。
 *
 * <p><b>本表不在契约 §2.9 的放行清单里</b>：它维持严格的 {@code tenant_id = ?} 过滤。
 * 放行会把超管本人的角色绑定暴露给每一个租户管理员 —— 契约 §2.9 的表格逐行说明过
 * 为什么放行范围只收到 {@code sys_role} 与 {@code sys_role_menu} 两张。
 *
 * <p>{@code uk_user_role(user_id, role_id, deleted_at)}：末尾的 {@code deleted_at}
 * 让「解绑再重绑」不撞唯一键 —— §2.3 的 {@code roleIds} 是全量覆盖（先删后插），
 * 反复改派角色是常规操作。
 */
@TableName("sys_user_role")
public class SysUserRole extends TenantEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    private Long roleId;

    public SysUserRole() {
    }

    public SysUserRole(Long userId, Long roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }
}
