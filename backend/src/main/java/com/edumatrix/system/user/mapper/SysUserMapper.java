package com.edumatrix.system.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.user.entity.SysUser;

/**
 * {@code sys_user} 的写侧读写（03-01 §2.2~§2.6）。
 *
 * <p><b>没有 {@code selectByUsername}</b>：那是登录链路的查询，必须跨租户，
 * 归 {@code auth/mapper/AuthUserMapper} 且由调用方用 {@code TenantHelper.ignore(...)} 包住。
 * 全库的 {@code ignore()} 调用点<b>仍然只有那一处</b>，本模块一处不加。
 *
 * <p><b>租户条件一律由插件注入</b>。{@code sys_user} 不在契约 §2.9 的放行清单里 ——
 * 放行会把超管本人的账号与手机号暴露给每一个租户管理员。超管读跨租户数据走的是
 * 「租户插件对超管整体放行」那条通道（{@code TenantHelper.isSuperAdminSession()}），
 * 两条通道不得混用。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按 {@code userId} 取其所在节点 —— 数据权限判定的起点（契约 §2.4）。
     *
     * <p>主键点查，命中 {@code PRIMARY}。只在 §2.1 列表与 §2.2 建号两条路径上各调一次，
     * 且超管那一支不走它。用它而不是读会话，理由见
     * {@code SysUserService#currentNodeId()} 的注释。
     */
    @Select("SELECT node_id FROM sys_user WHERE id = #{userId} AND deleted_at = 0")
    Long selectNodeIdById(@Param("userId") Long userId);

    /**
     * §2.5 重置密码：写新密文并置 {@code pwd_reset_flag = 1}（下次登录强制改密）。
     *
     * <p>用定向 UPDATE 而不是 {@code updateById(entity)}：与 {@code AuthUserMapper#updatePassword}
     * 同一条理由 —— 后者会把实体里所有非空字段一并写回，一次「重置密码」顺带重写
     * {@code status} / {@code node_id} 是没必要的风险面。
     *
     * <p><b>与 §1.6 本人改密的区别</b>：那边把 {@code pwd_reset_flag} 清 0（改完就不必再改），
     * 这边置 1（管理员给的是临时口令，本人必须再改一次，PRD F1-3 规则 3）。
     */
    @Update("UPDATE sys_user SET password = #{password}, pwd_reset_flag = 1 "
            + "WHERE id = #{userId} AND deleted_at = 0")
    int resetPassword(@Param("userId") Long userId, @Param("password") String password);

    /**
     * §2.6 启用/停用：只写 {@code status} 这一列。
     *
     * <p>本列是<b>与组织无关的账号级封禁</b>（契约 §2.3），仅超管可置。
     * <b>绝不在这里顺手写 {@code org_node.status}</b> —— 契约 §2.3 用一整段说明
     * 那条侧路为什么被拆掉：「停用可逆、启用不可逆」，账号侧的 1 再也没人改回来，
     * 而机构管理员没有任何接口能修复它。
     */
    @Update("UPDATE sys_user SET status = #{status} WHERE id = #{userId} AND deleted_at = 0")
    int updateStatus(@Param("userId") Long userId, @Param("status") Integer status);
}
