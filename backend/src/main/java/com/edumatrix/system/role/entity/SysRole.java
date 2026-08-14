package com.edumatrix.system.role.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code sys_role} 角色表（03-01 §3）。
 *
 * <h2>没有 {@code data_scope}，这不是漏写</h2>
 * <p>契约 §3：<b>操作权限由角色定、数据范围由树定</b>。DDL 的表注释逐字写着
 * 「无 data_scope 字段——全系统只有一条数据权限规则（本节点子树），
 * 不存在角色级数据档位」。同一个 {@code org_admin} 角色挂在机构根节点即"全机构"、
 * 挂在某校区节点即"该校区子树"，不需要第二个字段表达。
 * 因此本组接口的请求参数、响应体与示例中<b>均不出现 {@code dataScope}</b>。
 *
 * <h2>{@code tenant_id = 0} 的四行是全平台共用的同一行</h2>
 * <p>契约 §2.9 把本表的租户过滤放宽为 {@code (tenant_id = ? OR tenant_id = 0)}，
 * 让每个租户都<b>读得到</b>内置角色的定义（否则全员零权限、系统开箱不可用）。
 * <b>放宽的只是读</b> —— 写侧必须反向收紧，由 {@code system/role/service/PresetRoleGuard}
 * 统一承担：改预置角色的名称/状态/菜单绑定，会让<b>所有租户</b>跟着变
 * （停用预置 {@code teacher} 即全平台教师失权）。
 */
@TableName("sys_role")
public class SysRole extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 平台预置角色的 {@code tenant_id}（契约 §2.9）。
     *
     * <p><b>「是不是预置角色」一律按本值判定，不按 {@code role_key} 白名单。</b>
     * 白名单要在代码里复制一份「四个内置角色叫什么」，而那份副本与
     * {@code V202608120000__baseline.sql} 的种子数据分叉时不报错 ——
     * 表现是某个预置角色突然可以被 org_admin 改了。{@code tenant_id} 是数据自身携带的事实。
     */
    public static final long PLATFORM_TENANT_ID = 0L;

    private String roleName;

    /**
     * 角色标识。四个内置值见契约 §3：
     * {@code super_admin} / {@code org_admin} / {@code teacher} / {@code student}。
     *
     * <p>租户自建角色不得使用这四个值（§3.3 参数表），且
     * {@code uk_tenant_role_key(tenant_id, role_key, deleted_at)} 保证租户内唯一。
     */
    private String roleKey;

    /**
     * 0 正常 1 停用。
     *
     * <p><b>它对预置角色格外危险</b>：org_admin 停用平台预置的 {@code teacher} 角色，
     * 会让<b>全平台所有租户的教师</b>瞬间失权（§3.4 原文）。所以 §3.4 对 org_admin
     * 的拒绝不只针对 {@code roleKey}，{@code roleName} 与本列同样在列。
     */
    private Integer status;

    private Integer sort;

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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    /**
     * 是否为平台预置角色（{@code tenant_id = 0}）—— §3.1/§3.2 响应里的 {@code preset} 字段。
     *
     * <p>它同时是 {@code PresetRoleGuard} 的唯一判据。
     */
    public boolean isPreset() {
        return getTenantId() != null && getTenantId() == PLATFORM_TENANT_ID;
    }
}
