package com.edumatrix.support.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 探针 Mapper：<b>只用于验证租户插件的注入行为</b>，不是任何模块的产出。
 *
 * <p>之所以要走 MyBatis 而不是 {@code JdbcTemplate}：租户插件是 MyBatis 拦截器，
 * {@code JdbcTemplate} 完全绕过它。用 {@code JdbcTemplate} 去"验证租户隔离"
 * 只能验出 SQL 本身能不能跑。
 *
 * <p>包名落在 {@code ...mapper} 下，才会被 {@code @MapperScan("com.edumatrix.**.mapper")} 扫到。
 */
@Mapper
public interface ProbeMapper {

    /** 读角色。租户条件由插件注入 —— 这里一个字都不写。 */
    @Select("SELECT role_key FROM sys_role WHERE deleted_at = 0 ORDER BY role_key")
    List<String> selectRoleKeys();

    /** 读角色-菜单绑定的行数。 */
    @Select("SELECT COUNT(*) FROM sys_role_menu WHERE deleted_at = 0")
    int countRoleMenus();

    /**
     * 读操作日志。<b>反向对照组</b> —— {@code sys_oper_log} 同样存在 {@code tenant_id = 0} 的行
     * （超管的操作日志），但它<b>不在放行清单里</b>，必须读不到。
     */
    @Select("SELECT module FROM sys_oper_log WHERE deleted_at = 0 ORDER BY module")
    List<String> selectOperLogModules();

    /** 读用户名。{@code sys_user} 同样承载平台级行（超管账号），同样不放行。 */
    @Select("SELECT username FROM sys_user WHERE deleted_at = 0 ORDER BY username")
    List<String> selectUsernames();
}
