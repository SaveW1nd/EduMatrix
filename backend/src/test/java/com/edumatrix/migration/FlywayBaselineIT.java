package com.edumatrix.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.edumatrix.support.IntegrationTest;

/**
 * 模块 01 完成判据第 1 条：<b>空库执行 Flyway → 41 张表建出，
 * {@code vod_heartbeat_log} 的月分区就位</b>（04-实施计划.md 模块 01）。
 *
 * <p>Flyway 在 Spring 上下文启动时已经跑完，所以本类只需要查 {@code information_schema}。
 */
@IntegrationTest
class FlywayBaselineIT {

    private static final String SCHEMA = "edumatrix";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("41 张表建出（sys_10 + org_10 + crs_4 + vod_4 + qb_3 + hw_6 + stat_4，契约 §9.1）")
    void baselineCreates41Tables() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history'",
                Integer.class, SCHEMA);

        assertThat(count)
                .as("契约 §9.1：sys_10 + org_10 + crs_4 + vod_4 + qb_3 + hw_6 + stat_4 = 41。"
                        + "少了通常是有人为了'暂时用不到'删掉了 qb_ / hw_ 九张表 —— "
                        + "作业模块要到第 15 个才做，但表从第 1 天就要在库里")
                .isEqualTo(41);
    }

    @Test
    @DisplayName("七个前缀各自的表数与契约 §9.1 逐段对得上")
    void tableCountPerPrefix() {
        assertPrefixCount("sys\\_%", 10);
        assertPrefixCount("org\\_%", 10);
        assertPrefixCount("crs\\_%", 4);
        assertPrefixCount("vod\\_%", 4);
        assertPrefixCount("qb\\_%", 3);
        assertPrefixCount("hw\\_%", 6);
        assertPrefixCount("stat\\_%", 4);
    }

    private void assertPrefixCount(String pattern, int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_type = 'BASE TABLE' AND table_name LIKE ?",
                Integer.class, SCHEMA, pattern);
        assertThat(count).as("前缀 %s 的表数", pattern).isEqualTo(expected);
    }

    @Test
    @DisplayName("vod_heartbeat_log 的月分区就位，含 pmax")
    void heartbeatLogIsPartitioned() {
        List<String> partitions = jdbcTemplate.queryForList(
                "SELECT partition_name FROM information_schema.partitions "
                        + "WHERE table_schema = ? AND table_name = 'vod_heartbeat_log' "
                        + "AND partition_name IS NOT NULL ORDER BY partition_ordinal_position",
                String.class, SCHEMA);

        assertThat(partitions)
                .as("按月 RANGE 分区（契约 §7.3：亿级表，任何结构变更先在 pmax 之外的历史分区验证）")
                .containsExactly("p202608", "p202609", "p202610", "p202611", "p202612", "p202701", "pmax");
    }

    @Test
    @DisplayName("四个 Flyway 脚本全部 success，且基线版本号是 202608120000")
    void allMigrationsSucceeded() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class);

        // 202608150000 是模块 06 补的 teacher → org:node:list 角色绑定
        assertThat(versions).containsExactly(
                "202608120000", "202608140000", "202608140100", "202608150000");
    }

    @Test
    @DisplayName("菜单与角色绑定初始化数据已就位（F-1 定案 124 菜单 / 117 唯一 perms；绑定 200 + 模块 06 补的 1 行）")
    void menuAndRoleMenuInitialized() {
        Integer menus = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_menu", Integer.class);
        Integer bindings = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_role_menu", Integer.class);
        Integer perms = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE perms IS NOT NULL", Integer.class);

        assertThat(menus).as("契约 §10 附表 A 与 V202608140000 由同一份数据源生成").isEqualTo(124);
        assertThat(bindings)
                .as("F-1 ② 定案 student 不绑任何菜单行，故基线是 200；"
                        + "模块 06 的 V202608150000 补了 teacher → org:node:list，共 201")
                .isEqualTo(201);
        assertThat(perms).isEqualTo(117);
    }

    @Test
    @DisplayName("四个内置角色的 tenant_id 都是 0，且 sys_role_menu 的绑定行同样是 0")
    void builtInRolesArePlatformLevel() {
        Integer builtInRoles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role WHERE tenant_id = 0 AND deleted_at = 0", Integer.class);
        Integer nonZeroBindings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE tenant_id <> 0", Integer.class);

        assertThat(builtInRoles)
                .as("super_admin / org_admin / teacher / student 四个内置角色")
                .isEqualTo(4);
        assertThat(nonZeroBindings)
                .as("内置角色的菜单绑定一律 tenant_id = 0（契约 §10 脚注）")
                .isZero();
    }

    @Test
    @DisplayName("通用字段的例外表恰好是 6 张 —— 这是 BaseEntity / TenantEntity 继承关系的依据")
    void commonFieldExceptionsAreExactlySixTables() {
        // BaseEntity 的类注释里那张表格，在这里被钉成可执行断言。
        // 契约 §2.2 末尾允许日志/心跳明细表例外，但「哪几张、各缺什么」必须与 DDL 逐字一致 ——
        // 漂了就会出现「某张表继承了带 @TableLogic 的基类，而它根本没有 deleted_at 列」，
        // 表现是运行期 Unknown column，而不是编译错误。
        assertMissingColumn("tenant_id", "sys_menu", "sys_tenant");
        assertMissingColumn("create_by", "sys_login_log", "sys_oper_log",
                "vod_heartbeat_log", "vod_play_auth_log");
        assertMissingColumn("create_time", "sys_login_log", "sys_oper_log", "vod_heartbeat_log");
        assertMissingColumn("update_by", "sys_login_log", "sys_oper_log",
                "vod_heartbeat_log", "vod_play_auth_log");
        assertMissingColumn("remark", "sys_login_log", "sys_oper_log",
                "vod_heartbeat_log", "vod_play_auth_log");
        assertMissingColumn("update_time");
        assertMissingColumn("id");

        // 这一条单独拎出来：vod_heartbeat_log 没有 deleted_at，是它不能继承 BaseEntity 的硬理由。
        // 它按月 RANGE 分区、亿级写入，清理方式是归档删分区，不是逻辑删除。
        assertMissingColumn("deleted_at", "vod_heartbeat_log");
    }

    private void assertMissingColumn(String column, String... expectedTables) {
        List<String> actual = jdbcTemplate.queryForList(
                "SELECT t.table_name FROM information_schema.tables t "
                        + "WHERE t.table_schema = ? AND t.table_type = 'BASE TABLE' "
                        + "AND t.table_name <> 'flyway_schema_history' "
                        + "AND NOT EXISTS (SELECT 1 FROM information_schema.columns c "
                        + "                WHERE c.table_schema = t.table_schema "
                        + "                  AND c.table_name = t.table_name "
                        + "                  AND c.column_name = ?) "
                        + "ORDER BY t.table_name",
                String.class, SCHEMA, column);

        assertThat(actual)
                .as("缺少通用字段 %s 的表；与 BaseEntity 类注释里的表格必须逐字一致", column)
                .containsExactlyInAnyOrder(expectedTables);
    }

    @Test
    @DisplayName("sys_menu 与 sys_tenant 确实没有 tenant_id 列 —— 这是 ignoreTable 清单的依据")
    void platformTablesHaveNoTenantColumn() {
        List<String> tablesWithoutTenant = jdbcTemplate.queryForList(
                "SELECT t.table_name FROM information_schema.tables t "
                        + "WHERE t.table_schema = ? AND t.table_type = 'BASE TABLE' "
                        + "AND t.table_name <> 'flyway_schema_history' "
                        + "AND NOT EXISTS (SELECT 1 FROM information_schema.columns c "
                        + "                WHERE c.table_schema = t.table_schema "
                        + "                  AND c.table_name = t.table_name "
                        + "                  AND c.column_name = 'tenant_id') "
                        + "ORDER BY t.table_name",
                String.class, SCHEMA);

        assertThat(tablesWithoutTenant)
                .as("EduMatrixTenantLineHandler 的 ignoreTable 清单必须与这个结果逐字一致 —— "
                        + "ignoreTable 是整表开关，只适用于纯平台级、无租户列的表")
                .containsExactly("sys_menu", "sys_tenant");
    }
}
