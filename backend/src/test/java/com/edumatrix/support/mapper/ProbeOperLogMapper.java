package com.edumatrix.support.mapper;

import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 读 {@code sys_oper_log} 的探针 Mapper（<b>只在 {@code src/test}</b>）。
 *
 * <p>{@code system/log} 的查询 Mapper 是分页 + 条件的业务查询，不适合拿来做断言 ——
 * 断言要的是"最后一行长什么样"这种直白的东西。
 *
 * <p><b>{@code @Param("ignored") } 那一招在这里用不上</b>：租户插件会给本查询注入
 * {@code tenant_id}，而测试正是要验"这一行落在了哪个租户"。所以断言侧统一在
 * {@code TestCurrentContextProvider} 设好会话再查 —— 与被测路径走同一条通道。
 */
@Mapper
public interface ProbeOperLogMapper {

    /** 取指定 {@code action} 的最新一行（全列），供切面用例逐字段断言。 */
    @Select("SELECT id, user_id AS userId, module, action, method, params, ip, status, "
            + "error_msg AS errorMsg, cost_ms AS costMs, oper_time AS operTime, tenant_id AS tenantId "
            + "FROM sys_oper_log WHERE action = #{action} ORDER BY id DESC LIMIT 1")
    Map<String, Object> selectLatestByAction(@Param("action") String action);

    /** 某 {@code action} 的行数 —— 验「重放不重复记」「不该写时确实没写」。 */
    @Select("SELECT COUNT(*) FROM sys_oper_log WHERE action = #{action}")
    int countByAction(@Param("action") String action);
}
