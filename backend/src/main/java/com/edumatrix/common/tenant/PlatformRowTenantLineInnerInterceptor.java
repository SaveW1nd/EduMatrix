package com.edumatrix.common.tenant;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

/**
 * 租户拦截器：对 {@code sys_role} / {@code sys_role_menu} 注入
 * {@code (tenant_id = ? OR tenant_id = 0)}，其余表维持 MyBatis-Plus 的原生等值过滤。
 *
 * <h2>为什么必须继承拦截器，而不是只写 TenantLineHandler</h2>
 * <p>{@code TenantLineHandler.getTenantId()} 的返回值被父类拿去<b>拼等值式</b>：
 * <pre>
 * // MyBatis-Plus 3.5.17 TenantLineInnerInterceptor#buildTableExpression 原文
 * if (tenantLineHandler.ignoreTable(table.getName())) return null;
 * return new EqualsTo(getAliasColumn(table), tenantLineHandler.getTenantId());
 * </pre>
 * 形状被 {@code EqualsTo} 写死了，光实现 Handler 拼不出 {@code OR}。
 * 而 {@code getTenantId()} <b>同时</b>被 {@code processInsert} 用作 INSERT 的写入值 ——
 * 在那里返回一个 {@code OR} 表达式会直接把它写进数据行。所以放行逻辑只能落在
 * {@link #buildTableExpression} 这一处：<b>它只影响读条件，不影响写入值。</b>
 *
 * <h2>Parenthesis 不是排版，是正确性</h2>
 * <p>父类 {@code BaseMultiTableInnerInterceptor} 这样把注入条件接到原 WHERE 上：
 * <pre>
 * new AndExpression(currentExpression, injectExpression)
 * </pre>
 * 它<b>只在原 WHERE 是 OrExpression 时给原 WHERE 加括号，从不给注入的表达式加括号</b>。
 * 于是返回裸的 {@code OrExpression} 会渲染成：
 * <pre>
 * WHERE status = 0 AND tenant_id = 5 OR tenant_id = 0
 *    ≡ WHERE (status = 0 AND tenant_id = 5) OR tenant_id = 0     ← AND 优先级高于 OR
 * </pre>
 * 也就是<b>所有 {@code tenant_id = 0} 的行绕过其余全部条件</b>（包括 {@code deleted_at = 0}
 * 与业务筛选）。多表 JOIN 时更糟：父类用 AND 把各表条件串起来，一个裸 OR 会截断整条链。
 * 这类错误不会报错，只会让某些行莫名其妙地出现在结果里 ——
 * {@code PlatformRowTenantLineInnerInterceptorTest} 直接断言渲染后的 SQL 字面量含括号。
 *
 * <h2>放行的只是读，写侧必须反向收紧</h2>
 * <p>{@code sys_role} / {@code sys_role_menu} 的 {@code tenant_id = 0} 行是<b>全平台所有租户
 * 共用的同一行</b>，因此对 {@code org_admin} <b>全只读</b>：改预置角色的名称、状态或菜单绑定，
 * 会让所有租户跟着变（停用预置 {@code teacher} 角色即全平台教师失权）。这四行只有
 * {@code super_admin} 可改，且任何人不可删。<b>本拦截器不负责这条</b> —— 它归模块 03
 * （03-01 §3 导语与 §3.4 / §3.5 / §3.6）。放行解决的是"读不到自己的权限定义"，
 * 不是"可以改别人的"，两件事必须分别落实。
 */
public class PlatformRowTenantLineInnerInterceptor extends TenantLineInnerInterceptor {

    private final EduMatrixTenantLineHandler tenantLineHandler;

    public PlatformRowTenantLineInnerInterceptor(EduMatrixTenantLineHandler tenantLineHandler) {
        super(tenantLineHandler);
        this.tenantLineHandler = tenantLineHandler;
    }

    /**
     * {@inheritDoc}
     *
     * <p>与父类的差异只有一处：放行表返回 {@code (tenant_id = ? OR tenant_id = 0)}。
     * 其余分支逐字保持父类行为，包括 {@code ignoreTable} 返回 null 这一条 ——
     * 返回 null 表示"本表不注入任何条件"，由 {@code builderExpression} 过滤掉。
     */
    @Override
    public Expression buildTableExpression(final Table table, final Expression where, final String whereSegment) {
        if (tenantLineHandler.ignoreTable(table.getName())) {
            return null;
        }

        Expression currentTenantId = tenantLineHandler.getTenantId();
        EqualsTo ownTenant = new EqualsTo(getAliasColumn(table), currentTenantId);

        if (!tenantLineHandler.isPlatformRowTable(table.getName())) {
            // 绝大多数表走这里，与父类实现逐字相同
            return ownTenant;
        }

        // sys_role / sys_role_menu：(tenant_id = ? OR tenant_id = 0)
        // 括号不可省，理由见类注释 —— 少了它，平台级行会绕过 WHERE 里的其余全部条件
        EqualsTo platformRow = new EqualsTo(
                getAliasColumn(table),
                new LongValue(EduMatrixTenantLineHandler.PLATFORM_TENANT_ID));
        // 用 ParenthesedExpressionList 而不是 Parenthesis：
        // JSqlParser 5.2 的 Parenthesis 只有无参构造，setExpression/withExpression
        // 【不会把表达式放进底层列表】，toString() 直接 IndexOutOfBounds —— 编译得过、
        // 运行期才炸。ParenthesedExpressionList 正是 MyBatis-Plus 自己在
        // BaseMultiTableInnerInterceptor 里用来加括号的类型。
        return new ParenthesedExpressionList<Expression>(new OrExpression(ownTenant, platformRow));
    }

    /**
     * {@inheritDoc}
     *
     * <p>父类的实现会在有表别名时生成 {@code 别名.tenant_id}。这里原样沿用 —— 覆写它
     * 只是为了把可见性提到本包内可读，便于 {@link #buildTableExpression} 两次取列。
     */
    @Override
    protected Column getAliasColumn(Table table) {
        return super.getAliasColumn(table);
    }
}
