package com.edumatrix.system.log.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.edumatrix.system.log.dto.OperLogPageQuery;
import com.edumatrix.system.log.vo.OperLogVO;

/**
 * {@code sys_oper_log} 的<b>只读</b>查询（03-01 §8.2）—— F-25 列的第四件事。
 *
 * <h2>与 {@code common/operlog/mapper/OperLogMapper} 的分工</h2>
 * <p>那个是<b>唯一写入口</b>（只有 INSERT），本类是<b>唯一读入口</b>（只有 SELECT）。
 * 与 {@code sys_login_log} 那对读写分离是同一个形态，但成因不同：
 * 那边是因为写侧在 {@code auth} 域、读侧在 {@code system} 域、检查③ 不许互相 import；
 * 这边<b>两侧都能放在一起</b>，之所以仍然分开，是因为写入口必须在 {@code common}
 * （消费方跨 {@code org} / {@code vod} / {@code system} 三个域），
 * 而<b>查询接口是 {@code system/log} 的交付物</b>（05-工程结构.md §C2）。
 *
 * <p>{@code common} 里放一个业务查询会让公共层开始认识分页 DTO 与响应 VO ——
 * {@code common/operlog/mapper/OperLogMapper} 的类注释已经写死「<b>只有 INSERT</b>」。
 *
 * <h2>同样受检查⑥ 约束：本接口只允许 {@code @Select}</h2>
 * <p>往这里加一个 {@code @Update} 就等于给「操作日志可被篡改」开了口，
 * 而契约 §7.2 第 5 条要求这张表保留 ≥ 6 个月、且它是排查越权时唯一的原始事实。
 *
 * <h2>{@code LEFT JOIN sys_user} 的理由与 §8.1 相同，但更强</h2>
 * <p>§8.2 的响应含 {@code username} 与 {@code realName}，而 {@code sys_oper_log}
 * <b>只有 {@code user_id}</b>（基线 275~293 行）。而 {@code user_id} 在
 * <b>Job / Worker / 事件消费</b>路径上是 {@code null}（{@code OperLogWriter} 的四档表，
 * 契约 §2.8 规则 1）—— 内连接会把<b>全部无人值守的操作记录过滤掉</b>，
 * 包括模块 07 的删除请求脱敏留痕（PRD F7-3）与契约 §2.8 规则 3 的孤儿事件告警。
 * 那些恰恰是最需要被查到的行。
 */
@Mapper
public interface SysOperLogQueryMapper {

    /**
     * §8.2 分页查询操作日志。
     *
     * <p>{@code module} 模糊、{@code action} <b>精确</b>（§8.2 参数表逐字：
     * 「{@code action} | 操作类型，如 新增、修改、删除、导出，<b>精确匹配</b>」）。
     * 两者不同不是笔误：{@code module} 是给人筛页面用的，{@code action} 是给人筛动作用的，
     * 而动作词是穷举的中文值（见 {@code @OperLog} 类注释）。
     */
    @Select("<script>"
            + "SELECT o.id, o.user_id AS userId, u.username, u.real_name AS realName, "
            + "       o.module, o.action, o.method, o.params, o.ip, o.cost_ms AS costMs, "
            + "       o.status, o.error_msg AS errorMsg, o.oper_time AS operTime "
            + "FROM sys_oper_log o "
            + "LEFT JOIN sys_user u ON u.id = o.user_id "
            + "<where>"
            + "  <if test='q.module != null and q.module != \"\"'>AND o.module LIKE CONCAT('%', #{q.module}, '%')</if>"
            + "  <if test='q.action != null and q.action != \"\"'>AND o.action = #{q.action}</if>"
            + "  <if test='q.username != null and q.username != \"\"'>AND u.username LIKE CONCAT('%', #{q.username}, '%')</if>"
            + "  <if test='q.beginTime != null and q.beginTime != \"\"'>AND o.oper_time &gt;= #{q.beginTime}</if>"
            + "  <if test='q.endTime != null and q.endTime != \"\"'>AND o.oper_time &lt;= #{q.endTime}</if>"
            + "  <if test='q.tenantId != null'>AND o.tenant_id = #{q.tenantId}</if>"
            + "</where>"
            + " ORDER BY o.oper_time DESC, o.id DESC"
            + "</script>")
    IPage<OperLogVO> selectPage(IPage<OperLogVO> page, @Param("q") OperLogPageQuery query);
}
