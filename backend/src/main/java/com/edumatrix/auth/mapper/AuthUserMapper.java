package com.edumatrix.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.auth.entity.AuthUser;

/**
 * 登录链路对 {@code sys_user} 的读写。
 *
 * <p><b>租户条件一律由插件注入，这里一个字都不写。</b>唯一的例外是
 * {@link #selectByUsername}：它必须跨租户，理由见该方法注释，
 * 且逃生舱由<b>调用方</b>用 {@code TenantHelper.ignore(...)} 显式声明 ——
 * 放在调用点而不是 Mapper 里，是为了让 {@code grep -rn "TenantHelper.ignore"} 数得出来。
 */
@Mapper
public interface AuthUserMapper extends BaseMapper<AuthUser> {

    /**
     * 按登录账号取用户。<b>调用方必须用 {@code TenantHelper.ignore(...)} 包住。</b>
     *
     * <p>这是全系统唯一正当的跨租户查询：<b>登录那一刻还不知道租户是谁，
     * 租户恰恰是这次查询的结果</b>（{@code TenantHelper} 类注释逐字如此）。
     * 不包 {@code ignore} 的话，租户插件会走到 {@code requireTenantId()} 并抛
     * {@code TenantContextMissingException} —— 表现为「谁都登不进来」。
     *
     * <p>{@code uk_username(username, deleted_at)} 保证同一 {@code username}
     * 最多只有一行未删除记录，所以这里返回单行是安全的。
     */
    @Select("SELECT id, username, password, user_type AS userType, real_name AS realName, "
            + "phone, avatar, node_id AS nodeId, status, pwd_reset_flag AS pwdResetFlag, "
            + "last_login_time AS lastLoginTime, tenant_id AS tenantId "
            + "FROM sys_user WHERE username = #{username} AND deleted_at = 0")
    AuthUser selectByUsername(@Param("username") String username);

    /**
     * 回写最后登录时间。
     *
     * <p>用定向 UPDATE 而不是 {@code updateById(entity)}：后者会把实体里所有非空字段
     * 一并写回，而登录链路刚读出来的实体带着 {@code password} —— 一次「更新登录时间」
     * 顺带重写密码列是没必要的风险面。
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE sys_user SET last_login_time = #{loginTime} WHERE id = #{userId} AND deleted_at = 0")
    int updateLastLoginTime(@Param("userId") Long userId,
                            @Param("loginTime") java.time.LocalDateTime loginTime);

    /**
     * 本人改密：写新密文并清除强制改密标记（03-01 §1.6）。
     *
     * <p><b>只动这两列。</b>{@code sys_user.status} 在本模块任何路径下都不写 ——
     * 它是仅超管可置的账号级封禁（契约 §2.3）。
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE sys_user SET password = #{password}, pwd_reset_flag = 0 "
                    + "WHERE id = #{userId} AND deleted_at = 0")
    int updatePassword(@Param("userId") Long userId, @Param("password") String password);
}
