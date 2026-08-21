package com.edumatrix.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;

/**
 * 租户放行逻辑的 SQL 形状测试（契约 §2.9）。
 *
 * <p><b>这是本模块最容易做错的一处，所以断言的是渲染后的 SQL 字面量</b>，
 * 而不是"有没有调到某个方法"。
 */
class PlatformRowTenantLineInnerInterceptorTest {

    private final EduMatrixTenantLineHandler handler = new EduMatrixTenantLineHandler();
    private final PlatformRowTenantLineInnerInterceptor interceptor =
            new PlatformRowTenantLineInnerInterceptor(handler);

    @AfterEach
    void tearDown() {
        TenantHelper.reset();
        TenantHelper.setProvider(new NoSessionCurrentContextProvider());
    }

    /** 复现父类 {@code BaseMultiTableInnerInterceptor#appendExpression} 的拼接方式。 */
    private String combineWithWhere(String tableName, String whereSql) throws Exception {
        Expression where = CCJSqlParserUtil.parseCondExpression(whereSql);
        Expression injected = interceptor.buildTableExpression(new Table(tableName), where, "");
        return new AndExpression(where, injected).toString();
    }

    @Test
    @DisplayName("sys_role 注入 (tenant_id = ? OR tenant_id = 0)，括号不可省")
    void platformRowTableGetsParenthesizedOr() throws Exception {
        String sql = TenantHelper.runWithTenant(5L, () -> {
            try {
                return combineWithWhere("sys_role", "status = 0 AND deleted_at = 0");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(sql).isEqualTo("status = 0 AND deleted_at = 0 AND (tenant_id = 5 OR tenant_id = 0)");
    }

    @Test
    @DisplayName("少了括号就是越权：AND 优先级高于 OR，平台级行会绕过其余全部条件")
    void withoutParenthesisTheFilterWouldLeak() throws Exception {
        // 这条测试不测生产代码，它固化的是"为什么必须加括号"这个事实。
        // 若将来有人把 Parenthesis 去掉，上一条测试会失败；本条解释失败的后果。
        Expression where = CCJSqlParserUtil.parseCondExpression("status = 0");
        Expression bareOr = CCJSqlParserUtil.parseCondExpression("tenant_id = 5 OR tenant_id = 0");
        String leaked = new AndExpression(where, bareOr).toString();

        assertThat(leaked).isEqualTo("status = 0 AND tenant_id = 5 OR tenant_id = 0");
        // 它等价于 (status = 0 AND tenant_id = 5) OR tenant_id = 0
        // —— 也就是所有 tenant_id = 0 的行不受 status 约束
        Expression reparsed = CCJSqlParserUtil.parseCondExpression(leaked);
        assertThat(reparsed.toString()).isEqualTo("status = 0 AND tenant_id = 5 OR tenant_id = 0");
        assertThat(reparsed).isInstanceOf(net.sf.jsqlparser.expression.operators.conditional.OrExpression.class);
    }

    @Test
    @DisplayName("sys_role_menu 同样放行")
    void sysRoleMenuIsPlatformRowTable() {
        assertThat(handler.isPlatformRowTable("sys_role_menu")).isTrue();
        assertThat(handler.isPlatformRowTable("sys_role")).isTrue();
        assertThat(EduMatrixTenantLineHandler.platformRowTables())
                .as("放行表只有 2 张。加表等于放宽跨租户可见范围，必须先改契约 §2.9")
                .containsExactlyInAnyOrder("sys_role", "sys_role_menu");
    }

    @Test
    @DisplayName("其余表严格等值过滤，一个字都不放行")
    void otherTablesStayStrict() throws Exception {
        for (String table : new String[]{"sys_user", "sys_user_role", "sys_file",
                                         "sys_login_log", "sys_oper_log", "org_node"}) {
            String sql = TenantHelper.runWithTenant(5L, () -> {
                try {
                    return combineWithWhere(table, "deleted_at = 0");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(sql)
                    .as("%s 放行 tenant_id = 0 会把超管的账号/手机号/登录轨迹/操作日志"
                            + "暴露给每个租户管理员（契约 §2.9）", table)
                    .isEqualTo("deleted_at = 0 AND tenant_id = 5")
                    .doesNotContain("OR");
        }
    }

    @Test
    @DisplayName("表别名要带上：r.tenant_id 而不是裸 tenant_id")
    void aliasIsRespected() {
        Table table = new Table("sys_role");
        table.setAlias(new Alias("r"));
        Expression injected = TenantHelper.runWithTenant(5L,
                () -> interceptor.buildTableExpression(table, null, ""));
        assertThat(injected.toString()).isEqualTo("(r.tenant_id = 5 OR r.tenant_id = 0)");
    }

    @Test
    @DisplayName("sys_menu / sys_tenant 没有 tenant_id 列，压根不进插件")
    void tablesWithoutTenantColumnAreIgnored() {
        assertThat(EduMatrixTenantLineHandler.tablesWithoutTenantColumn())
                .containsExactlyInAnyOrder("sys_menu", "sys_tenant");
        TenantHelper.runWithTenant(5L, () -> {
            assertThat(interceptor.buildTableExpression(new Table("sys_menu"), null, "")).isNull();
            assertThat(interceptor.buildTableExpression(new Table("sys_tenant"), null, "")).isNull();
        });
    }

    @Test
    @DisplayName("表名带反引号或库名前缀时仍能正确识别")
    void tableNameIsNormalized() {
        assertThat(handler.isPlatformRowTable("`sys_role`")).isTrue();
        assertThat(handler.isPlatformRowTable("edumatrix.sys_role")).isTrue();
        assertThat(handler.isPlatformRowTable("SYS_ROLE")).isTrue();
        assertThat(handler.ignoreTable("`sys_menu`")).isTrue();
    }

    @Test
    @DisplayName("ignore() 上下文里整表放行；超管会话同样整体放行")
    void escapeHatches() {
        TenantHelper.ignore(() -> {
            assertThat(interceptor.buildTableExpression(new Table("sys_user"), null, "")).isNull();
        });

        TenantHelper.setProvider(new CurrentContextProvider() {
            @Override
            public Long getTenantId() {
                return null;
            }

            @Override
            public boolean isSuperAdmin() {
                return true;
            }

            @Override
            public Long getUserId() {
                return 1L;
            }

            @Override
            public Long getNodeId() {
                return 0L;
            }

    @Override
    public Integer getUserType() {
        return null;
    }
        });
        assertThat(interceptor.buildTableExpression(new Table("sys_oper_log"), null, ""))
                .as("超管靠租户插件整体放行，这是与 tenant_id = 0 放行不同的第二条通道")
                .isNull();
    }

    @Test
    @DisplayName("拿不到租户上下文时让 SQL 失败，绝不退化为无租户条件")
    void missingTenantContextFailsLoudly() {
        assertThatThrownBy(() -> interceptor.buildTableExpression(new Table("org_student"), null, ""))
                .isInstanceOf(TenantContextMissingException.class);
    }
}
