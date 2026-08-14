package com.edumatrix.system.user.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.user.entity.SysUserRole;

/** {@code sys_user_role} 的读写（03-01 §2.2 / §2.3 的 {@code roleIds} 全量覆盖）。 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /** 该用户当前绑定的角色 ID。{@code 10012}「降权自己」的判定要用它。 */
    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId} AND deleted_at = 0")
    List<Long> selectRoleIdsByUser(@Param("userId") Long userId);

    /**
     * 校验一批 {@code roleIds} 里有几个是当前会话可见的角色。
     *
     * <p><b>可见性由租户插件决定</b>：org_admin 看得到「本租户 + 平台预置」
     * （契约 §2.9 的放行），超管看得到全部。因此这一条查询同时完成了
     * 「角色存在」与「不是别的租户的自建角色」两件事 —— 不必也不应再写 {@code tenant_id} 条件。
     */
    @Select("<script>"
            + "SELECT COUNT(1) FROM sys_role WHERE deleted_at = 0 AND id IN "
            + "<foreach collection='roleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    long countVisibleRoles(@Param("roleIds") List<Long> roleIds);
}
