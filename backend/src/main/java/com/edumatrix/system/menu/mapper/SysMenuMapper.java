package com.edumatrix.system.menu.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.menu.entity.SysMenu;

/**
 * {@code sys_menu} 的读写（03-01 §4）。
 *
 * <p><b>本表不带 {@code tenant_id}，不进租户插件</b>（契约 §2.9：全库只有它与
 * {@code sys_tenant} 两张纯平台级表）。所以这里既不会被注入租户条件，也<b>绝不能</b>
 * 手写 {@code OR tenant_id = 0} —— 那一列根本不存在。
 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /**
     * 某用户实际拥有的菜单 ID 集合（{@code sys_user_role → sys_role_menu}）。
     *
     * <p><b>§3.3 / §3.6 防提权与 §4.1 菜单树裁剪的唯一数据来源。</b>
     * 03-01 §3.6：「org_admin 可分配的菜单<b>不得超出 org_admin 自身拥有的菜单集合</b>」。
     *
     * <p><b>不过滤 {@code sys_menu.status} / {@code visible}</b>：
     * 隐藏菜单仍参与权限计算（§4.1 字段说明），而「停用的菜单是否仍授权」全套文档未定义 ——
     * 与 {@code AuthPermMapper} 保持同一口径，两处必须一致，
     * 否则会出现「{@code /auth/me} 给了这个 perms、但分配菜单时说你没有」。
     *
     * <p>{@code sys_role} / {@code sys_role_menu} 的 {@code tenant_id = 0} 放行由插件
     * 注入（契约 §2.9），<b>这里一个字不写</b> —— 少了那个放行，本查询对非超管恒返回空集，
     * 表现为「org_admin 分配任何菜单都被判越权」。
     */
    @Select("SELECT DISTINCT rm.menu_id "
            + "FROM sys_user_role ur "
            + "JOIN sys_role_menu rm ON rm.role_id = ur.role_id AND rm.deleted_at = 0 "
            + "WHERE ur.user_id = #{userId} AND ur.deleted_at = 0")
    List<Long> selectOwnedMenuIds(@Param("userId") Long userId);

    /** 直接子菜单数量（{@code 10009} 的第一个判据）。命中 {@code idx_parent_id}。 */
    @Select("SELECT COUNT(1) FROM sys_menu WHERE parent_id = #{menuId} AND deleted_at = 0")
    long countChildren(@Param("menuId") Long menuId);

    /** 被角色引用的绑定数（{@code 10009} 的第二个判据）。命中 {@code idx_menu_id}。 */
    @Select("SELECT COUNT(1) FROM sys_role_menu WHERE menu_id = #{menuId} AND deleted_at = 0")
    long countRoleBindings(@Param("menuId") Long menuId);

    /**
     * 校验一批 {@code menuIds} 里有几个是真实存在的菜单（§3.3 / §3.6 的「含无效 ID → 400」）。
     *
     * <p>只回数量不回明细：调用方要判的是「全部有效」这一个布尔，
     * 而把不存在的 ID 逐个回给前端等于提供一个菜单存在性探测口。
     */
    @Select("<script>"
            + "SELECT COUNT(1) FROM sys_menu WHERE deleted_at = 0 AND id IN "
            + "<foreach collection='menuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    long countExisting(@Param("menuIds") List<Long> menuIds);
}
