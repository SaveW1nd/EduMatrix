package com.edumatrix.org.member.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code sys_oper_log} 的<b>窄只写</b>，只服务模块 07 的两条合规留痕
 * （见 {@code MemberOperLogWriter} 的类注释）。
 *
 * <h2>这是工单已授权的路径</h2>
 * <p>04-实施计划.md 模块 07 的「涉及表」<b>写</b>栏明列 {@code sys_oper_log}。
 *
 * <h2>只有 INSERT，没有查询</h2>
 * <p>{@code sys_oper_log} 的<b>查询接口是模块 05 的交付物</b>（03-01 §8.2），
 * 本模块不做，也不该做 —— 那会在模块 05 落地时变成两份实现要合。
 *
 * <p><b>{@code tenant_id} 显式传入</b>：脱敏任务没有会话，插件注入不了
 * （契约 §2.8 规则 1「从数据显式取」）。{@code oper_time} / {@code status} / {@code cost_ms}
 * 一律走 DDL 默认值 —— 时间只认数据库这一个时钟。
 */
@Mapper
public interface MemberOperLogMapper {

    @Insert("INSERT INTO sys_oper_log (id, user_id, module, action, method, tenant_id) "
            + "VALUES (#{id}, #{userId}, #{module}, #{action}, #{method}, #{tenantId})")
    int insert(@Param("id") Long id,
               @Param("userId") Long userId,
               @Param("module") String module,
               @Param("action") String action,
               @Param("method") String method,
               @Param("tenantId") Long tenantId);
}
