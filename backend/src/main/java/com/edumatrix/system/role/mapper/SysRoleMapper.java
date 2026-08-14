package com.edumatrix.system.role.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.role.entity.SysRole;

/**
 * {@code sys_role} 的读写（03-01 §3）。
 *
 * <p><b>租户条件由插件注入，且本表走的是放行分支</b>
 * {@code (tenant_id = ? OR tenant_id = 0)}（契约 §2.9）。所以：
 * <ul>
 *   <li>org_admin 读到的是「本租户角色 + 平台预置角色」—— §3.1 数据权限原文，
 *       <b>不要在业务代码里手写这个 OR</b>（{@code check_backend_conventions.sh} 检查①）；
 *   <li>super_admin 读到全量 —— 走的是另一条通道（{@code TenantHelper.isSuperAdminSession()}
 *       让 {@code ignoreTable} 返回 true），与放行不是一回事，不得混用。
 * </ul>
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 该角色被多少个未删除用户引用（{@code 10008} 的判据）。命中 {@code idx_role_id}。
     *
     * <p><b>不 JOIN {@code sys_user}</b>：{@code sys_user_role} 的行随用户逻辑删除一并
     * 逻辑删除（§2.4 的删除路径会清它），所以只看关联表就够；JOIN 反而会把
     * 「用户已删但关联行没清」这种脏数据判成"可以删角色"，掩盖真正的问题。
     */
    @Select("SELECT COUNT(1) FROM sys_user_role WHERE role_id = #{roleId} AND deleted_at = 0")
    long countUserBindings(@Param("roleId") Long roleId);
}
