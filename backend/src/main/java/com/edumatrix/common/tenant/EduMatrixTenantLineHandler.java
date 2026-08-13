package com.edumatrix.common.tenant;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

/**
 * EduMatrix 的租户处理器（契约 §2.1 硬边界 + §2.9 平台级行放行）。
 *
 * <h2>三类表，三种处置</h2>
 * <table border="1">
 *   <caption>租户过滤的分类</caption>
 *   <tr><th>类别</th><th>表</th><th>处置</th></tr>
 *   <tr>
 *     <td>无 {@code tenant_id} 列的纯平台级表</td>
 *     <td>{@code sys_menu}、{@code sys_tenant}（<b>穷举，实测确认全库仅此 2 张</b>）</td>
 *     <td>{@link #ignoreTable} 返回 true，压根不进插件</td>
 *   </tr>
 *   <tr>
 *     <td>同时装着平台级行与租户私有行、且平台级行本就该被所有租户读到</td>
 *     <td>{@code sys_role}、{@code sys_role_menu}</td>
 *     <td>注入 {@code (tenant_id = ? OR tenant_id = 0)}，见
 *         {@link PlatformRowTenantLineInnerInterceptor}</td>
 *   </tr>
 *   <tr><td>其余全部</td><td>—</td><td>严格 {@code tenant_id = ?}</td></tr>
 * </table>
 *
 * <h2>为什么 {@code ignoreTable} 只能用在那 2 张表上</h2>
 * <p>{@code ignoreTable} 是<b>整表开关</b>：一旦忽略，该表就完全失去租户过滤。契约 §2.9
 * 的表格显示，承载平台级行的 7 张表<b>没有一张是纯平台级的</b>，每一张都同时装着租户私有行 ——
 * 忽略 {@code sys_role} 意味着租户 A 能列出、甚至改删租户 B 自建的「教务主任」角色；
 * 忽略 {@code sys_oper_log} 意味着操作日志跨租户全裸。
 * {@code ignoreTable} 只适用于<b>纯平台级、无租户列</b>的表，本系统里那就是
 * {@code sys_menu} 与 {@code sys_tenant} —— 它们本来就不带 {@code tenant_id}，压根不进插件。
 *
 * <h2>为什么放行范围只收到 2 张表</h2>
 * <p>{@code OR tenant_id = 0} 的代价是把该表的平台级行暴露给所有租户，所以只在
 * 「平台级行本就该被所有租户读到」时才成立：
 * <ul>
 *   <li><b>成立</b> —— {@code sys_role} / {@code sys_role_menu}：四个内置角色是全租户共用的定义，
 *       租户看到它们是设计意图（03-01 §3.1 早已写明数据权限是「org_admin 仅本租户角色 + 平台预置角色」）；
 *   <li><b>不成立</b> —— 另外 5 张（{@code sys_user}、{@code sys_user_role}、{@code sys_file}、
 *       {@code sys_login_log}、{@code sys_oper_log}）：放行会把<b>超管本人的账号、手机号、
 *       登录轨迹与操作日志暴露给每一个租户管理员</b>。超管读自己这些行不靠放行，
 *       而靠<b>租户插件对超管整体放行</b>（{@link TenantHelper#isSuperAdminSession()}）——
 *       这是两条不同的通道，不要混用。
 * </ul>
 *
 * <h2>{@code org_node} 的哨兵行不放行，且不需要放行</h2>
 * <ol>
 *   <li>登录停用校验不受影响：§2.3 的祖先链查询条件是 {@code node_type = 1 AND status = 1}，
 *       而哨兵行是 {@code node_type = 0}，<b>永远不可能命中</b>；
 *   <li>面包屑本就不该显示它：{@code nodePath} 的口径是「自<b>租户根</b>到自身」，
 *       平台根不属于任何租户，出现在租户的面包屑里反而是越界；
 *   <li>因此解析 {@code ancestors} 时必须跳过首位哨兵 {@code 0}，按 {@code IN} 查名称时
 *       返回行数比 id 数少 1 <b>是正确行为，不是 bug</b>。见 {@code common/subtree}。
 * </ol>
 *
 * <h2>新增带 {@code tenant_id} 的表时</h2>
 * <p>必须先回答「这张表会不会有 {@code tenant_id = 0} 的行」，答案为是则先在契约 §2.9
 * 的表格里登记并定案，<b>否则默认严格过滤</b>。不要直接往本类的常量里加表名。
 */
public class EduMatrixTenantLineHandler implements TenantLineHandler {

    /** 租户列名。全库统一 {@code tenant_id}（契约 §2.2 通用字段）。 */
    public static final String TENANT_ID_COLUMN = "tenant_id";

    /** 平台级行的租户 ID。契约 §2.9：内置角色与其菜单绑定一律 {@code tenant_id = 0}。 */
    public static final long PLATFORM_TENANT_ID = 0L;

    /**
     * 没有 {@code tenant_id} 列的表 —— 压根不进插件（契约 §2.9 / 04-实施计划.md 模块 01 规则 5）。
     * <b>穷举</b>：对 41 张表的 DDL 逐表实测，只有这 2 张不带该列。
     */
    private static final Set<String> TABLES_WITHOUT_TENANT_COLUMN =
            Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
                    "sys_menu",
                    "sys_tenant")));

    /**
     * 放行 {@code tenant_id = 0} 的表 —— <b>只有这 2 张</b>（契约 §2.9 定案）。
     * 往这里加表名等于放宽跨租户可见范围，必须先改契约。
     */
    private static final Set<String> PLATFORM_ROW_TABLES =
            Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
                    "sys_role",
                    "sys_role_menu")));

    @Override
    public Expression getTenantId() {
        // requireTenantId 而非 getTenantIdOrNull：拿不到租户就让这条 SQL 失败，
        // 而不是让它变成一条没有租户条件的全库查询（契约 §2.8 规则 3）
        return new LongValue(TenantHelper.requireTenantId());
    }

    @Override
    public String getTenantIdColumn() {
        return TENANT_ID_COLUMN;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        String name = normalize(tableName);
        // ① 无租户列的纯平台级表
        if (TABLES_WITHOUT_TENANT_COLUMN.contains(name)) {
            return true;
        }
        // ② 显式 ignore 逃生舱（登录按用户名查 sys_user 这类必须跨租户的查询）
        if (TenantHelper.isIgnored()) {
            return true;
        }
        // ③ 超管会话整体放行 —— 与 sys_role 的 OR tenant_id = 0 是两条不同通道
        return TenantHelper.isSuperAdminSession();
    }

    /** 该表是否放行 {@code tenant_id = 0} 的平台级行。仅 {@code sys_role} / {@code sys_role_menu}。 */
    public boolean isPlatformRowTable(String tableName) {
        return PLATFORM_ROW_TABLES.contains(normalize(tableName));
    }

    /** 放行表清单的只读视图，供测试与启动期自检使用。 */
    public static Set<String> platformRowTables() {
        return PLATFORM_ROW_TABLES;
    }

    /** 无租户列表清单的只读视图，供测试与启动期自检使用。 */
    public static Set<String> tablesWithoutTenantColumn() {
        return TABLES_WITHOUT_TENANT_COLUMN;
    }

    /**
     * 表名归一：去反引号、去 schema 前缀、转小写。
     *
     * <p>MyBatis-Plus 把 JSqlParser 解析出的原始表名直接传进来，可能是 {@code `sys_role`}
     * 或 {@code edumatrix.sys_role}。不归一就会让 {@code `sys_role`} 匹配不上清单 ——
     * 而匹配不上的后果是<b>静默地按普通表严格过滤</b>，也就是 §2.9 那个「每个非超管用户零权限」。
     */
    private static String normalize(String tableName) {
        if (tableName == null) {
            return "";
        }
        String name = tableName.replace("`", "").replace("\"", "").trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
