package com.edumatrix.system.log.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.edumatrix.system.log.dto.LoginLogPageQuery;
import com.edumatrix.system.log.vo.LoginLogVO;

/**
 * {@code sys_login_log} 的<b>只读</b>查询（03-01 §8.1）。
 *
 * <h2>⚠ 同一张表的第二个 Mapper —— 这是有意的，且带三条硬约束</h2>
 * <p><b>写侧在 {@code auth/mapper/AuthLoginLogMapper}</b>（登录成功与失败都留痕，PRD F1-1），
 * 那个类的注释<b>早就预告了本类</b>，逐字：「本模块<b>只写不读</b> ——
 * 登录日志的<b>查询</b>接口是 03-01 §8.1，<b>归模块 05</b>」。
 *
 * <p>为什么不合并成一个：
 * <ol>
 *   <li>§8.1 的查询接口按 05-工程结构.md §C2 落 {@code system/log/}，
 *       而 {@code check_backend_conventions.sh} 检查③ <b>禁止 {@code system} import {@code auth}</b>；</li>
 *   <li>反向做成 SPI（接口在 {@code common}、实现在 {@code auth}）要把 7 个查询条件 + 分页
 *       的 DTO 与 VO 都塞进 {@code common}，让公共层开始认识业务查询字段；
 *       且 {@code auth} 已经实现了 {@code common/account} 的两个 SPI，
 *       再让 {@code system/log} 反过来被 {@code auth} 调，构造器循环风险是真的
 *       （{@code NodeAccountMapper} 类注释点过这一条）；</li>
 *   <li><b>两者不共享任何 SQL</b>：写侧是 INSERT 全列，读侧是 SELECT + WHERE + 分页 + JOIN。
 *       真正危险的「两份同源实现」是同一段逻辑写两遍（如 {@code MemberOperLogMapper} 那种），
 *       这里不是。</li>
 * </ol>
 * <p>工单依据：{@code 04-实施计划.md} 模块 05 的「涉及表」<b>只读</b>栏逐字列着
 * {@code sys_login_log} —— 与 {@code NodeAccountMapper} 那条「工单已授权的路径」同源。
 *
 * <h2>三条硬约束（补上「检查③ 拦 import 不拦表」这个空缺）</h2>
 * <ol>
 *   <li><b>本接口只允许 {@code @Select}</b>。{@code check_backend_conventions.sh} 的
 *       <b>检查⑥</b> 会 grep 本文件里的 {@code @Insert}/{@code @Update}/{@code @Delete}，
 *       命中即失败 —— 加一个进来立刻红；</li>
 *   <li><b>列变更须同时改两处</b>：{@code auth/entity/AuthLoginLog} 与本类的 SELECT 列表。
 *       {@code AuthLoginLogMapper} 侧有一条对称注释指回这里；</li>
 *   <li>不建实体，只出 {@link LoginLogVO}（§C2：「{@code vo/} 响应体。<b>与 entity 分开</b>」）。</li>
 * </ol>
 *
 * <h2>{@code LEFT JOIN} 不是 {@code JOIN} —— 这一处写错是完全静默的</h2>
 * <p>§8.1 的响应含 {@code realName}，而 {@code sys_login_log} <b>没有这一列</b>
 * （基线 254~269 行只有 {@code username}），必须回 {@code sys_user} 取。
 * 而 {@code user_id} 的 DDL 注释逐字「登录失败且<b>账号不存在</b>时为 {@code NULL}」——
 * 用内连接会把<b>撞库失败的记录全部过滤掉</b>，而那正是这张表最重要的用途。
 * 表现：接口 200、有数据、就是查不到那批攻击记录。
 * {@code SysLogQueryIT#failedLoginWithUnknownAccountIsStillListed} 守这一条。
 *
 * <p>{@code sys_user} 与本类同属 {@code system} 域，检查③ 不拦；
 * 但它<b>不加 {@code deleted_at = 0}</b> —— 用户被删之后，他的历史登录记录仍然要查得到，
 * 那是审计不是用户列表。
 */
@Mapper
public interface SysLoginLogQueryMapper {

    /**
     * §8.1 分页查询登录日志。
     *
     * <p><b>租户条件由插件注入，本类一个字都不写</b>：{@code sys_login_log} 有
     * {@code tenant_id} 列且不在契约 §2.9 的平台级放行清单里。
     * org_admin 只看本租户、super_admin 整体放行（可另传 {@code tenantId} 过滤）——
     * 那条 {@code tenantId} 参数在 WHERE 里是<b>额外收窄</b>，不是租户隔离本身。
     */
    @Select("<script>"
            + "SELECT l.id, l.user_id AS userId, l.username, u.real_name AS realName, "
            + "       l.ip, l.user_agent AS userAgent, l.status, l.msg, l.login_time AS loginTime "
            + "FROM sys_login_log l "
            + "LEFT JOIN sys_user u ON u.id = l.user_id "
            + "<where>"
            + "  <if test='q.username != null and q.username != \"\"'>AND l.username LIKE CONCAT('%', #{q.username}, '%')</if>"
            + "  <if test='q.status != null'>AND l.status = #{q.status}</if>"
            + "  <if test='q.ip != null and q.ip != \"\"'>AND l.ip = #{q.ip}</if>"
            + "  <if test='q.beginTime != null and q.beginTime != \"\"'>AND l.login_time &gt;= #{q.beginTime}</if>"
            + "  <if test='q.endTime != null and q.endTime != \"\"'>AND l.login_time &lt;= #{q.endTime}</if>"
            + "  <if test='q.tenantId != null'>AND l.tenant_id = #{q.tenantId}</if>"
            + "</where>"
            + " ORDER BY l.login_time DESC, l.id DESC"
            + "</script>")
    IPage<LoginLogVO> selectPage(IPage<LoginLogVO> page, @Param("q") LoginLogPageQuery query);
}
