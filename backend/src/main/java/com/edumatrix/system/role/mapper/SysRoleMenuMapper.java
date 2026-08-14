package com.edumatrix.system.role.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.role.entity.SysRoleMenu;

/**
 * {@code sys_role_menu} 的读写（03-01 §3.2 回显 / §3.3 建角色 / §3.6 分配菜单）。
 *
 * <p>放行表，租户条件由插件注入 {@code (tenant_id = ? OR tenant_id = 0)}（契约 §2.9）。
 * 这里一个字不写 —— 少了那个放行，§3.2 查预置角色详情时 {@code menuIds} 恒为空数组，
 * 前端的"分配菜单"弹窗一个勾都不回显，而接口返回 200。
 */
@Mapper
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenu> {

    /** 角色当前绑定的菜单 ID（§3.2 的 {@code menuIds} 回显）。 */
    @Select("SELECT menu_id FROM sys_role_menu "
            + "WHERE role_id = #{roleId} AND deleted_at = 0 ORDER BY menu_id")
    List<Long> selectMenuIdsByRole(@Param("roleId") Long roleId);
}
