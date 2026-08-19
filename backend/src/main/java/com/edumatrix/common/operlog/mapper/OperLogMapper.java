package com.edumatrix.common.operlog.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code sys_oper_log} 的<b>唯一</b>写入口。只有 INSERT。
 *
 * <p><b>包名末段必须是 {@code mapper}</b>：{@code EduMatrixApplication} 上是
 * {@code @MapperScan("com.edumatrix.**.mapper")}，放在 {@code common/operlog/} 下扫不到，
 * 表现是启动期 {@code NoSuchBeanDefinitionException}（响亮失败，不是静默）。
 * 与 {@code common/subtree/mapper}、{@code common/frozen/mapper} 同构。
 *
 * <h2>为什么在 {@code common/} 而不是 {@code system/log/}</h2>
 * <p>消费方跨三个领域，而 {@code check_backend_conventions.sh} 检查③ 禁止领域包互相 import：
 * <ul>
 *   <li>{@code common/operlog/OperLogAspect} —— 全库带 {@code @OperLog} 的写接口；</li>
 *   <li>{@code org/member/service/MemberOperLogWriter} —— 模块 07 的两条<b>合规留痕</b>
 *       （契约 §7.2 第 1 条监护人同意、PRD F7-3 删除请求脱敏）；</li>
 *   <li>将来 {@code vod/media} —— 契约 §2.8 规则 3 逐字「事件消费若反查不到媒资行，
 *       <b>记 {@code sys_oper_log}</b>」。</li>
 * </ul>
 * <p>放进任何一个领域包，另外两个都 import 不了。与 {@code common/account/PasswordHasher}
 * 是同一个位置选择。
 *
 * <h2>本类取代了 {@code org/member/mapper/MemberOperLogMapper}（F-25 收敛）</h2>
 * <p>那个 Mapper <b>已删除</b>。模块 07 当时的论证是「切面写 {@code action=创建学生}、
 * 本方案写 {@code action=监护人同意留痕}，<b>两行语义不同</b>，可永久共存」——
 * 这条论证在<b>数据</b>层面成立（合规证据与运维审计确实不能合成一行：
 * {@code sys_oper_log} 的 DDL 表注释逐字「可按时间归档清理」，合并后一次归档会把证据一起清掉），
 * 但它回答的<b>不是</b>「两份同源实现」那个问题 —— 需方担心的是两条<b>代码路径</b>
 * 各带一套截断规则、脱敏规则与 {@code tenant_id} 取值口径。
 *
 * <p>所以收敛的是<b>写入实现</b>，不是语义：{@code MemberOperLogWriter} 与它的两个
 * {@code action} 常量、两个调用点<b>全部保留</b>，只是底层改调 {@link OperLogWriter}。
 * 收敛后不存在「改一份必须改另一份」，因此本类<b>不需要</b>那种警告注释，
 * 也不需要往下游模块塞收敛标记（F-27 那种四处标记是给「当场收敛不了」的情况用的）。
 *
 * <h2>{@code tenant_id} 显式传入，不靠插件注入</h2>
 * <p>超管会话下租户插件<b>整体放行</b>（{@code TenantHelper} 类注释路径③），
 * INSERT 不会补列 → 落 DDL 默认值 0。对超管来说 0 恰好是对的（平台级），
 * 但那是<b>碰巧对</b>。显式传参让它变成有意的，取值口径见 {@link OperLogWriter}。
 * MyBatis-Plus 在列已存在于 INSERT 列表时不会重复注入，此路径模块 07 已验证可用。
 *
 * <p>{@code oper_time} 一律走 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP} ——
 * <b>只认数据库这一个时钟</b>。
 */
@Mapper
public interface OperLogMapper {

    @Insert("INSERT INTO sys_oper_log "
            + "(id, user_id, module, action, method, params, ip, status, error_msg, cost_ms, tenant_id) "
            + "VALUES (#{id}, #{userId}, #{module}, #{action}, #{method}, #{params}, #{ip}, "
            + "#{status}, #{errorMsg}, #{costMs}, #{tenantId})")
    int insert(@Param("id") Long id,
               @Param("userId") Long userId,
               @Param("module") String module,
               @Param("action") String action,
               @Param("method") String method,
               @Param("params") String params,
               @Param("ip") String ip,
               @Param("status") Integer status,
               @Param("errorMsg") String errorMsg,
               @Param("costMs") Integer costMs,
               @Param("tenantId") Long tenantId);
}
