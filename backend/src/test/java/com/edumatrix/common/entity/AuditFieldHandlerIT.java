package com.edumatrix.common.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestCurrentContextProvider;
import com.edumatrix.support.mapper.ProbeRoleMapper;

/**
 * 署名字段自动填充（{@link AuditFieldHandler}）。
 *
 * <p>核心断言是<b>无会话时 {@code create_by IS NULL}，而不是 0</b> ——
 * 0 在本系统里不是空值，是平台根节点的 ID，填 0 会造出一条指向真实对象的假记录。
 */
@IntegrationTest
class AuditFieldHandlerIT {

    private static final long TENANT_A = 1953827104412590001L;
    private static final long OPERATOR_ID = 1953827104412590102L;
    private static final long OPERATOR_NODE = 1953827104412590001L;

    @Autowired
    private ProbeRoleMapper roleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestCurrentContextProvider context;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM sys_role WHERE role_key LIKE 'probe_audit_%'");
        context.asNoSession();
        TenantHelper.reset();
    }

    private ProbeRoleMapper.ProbeRole newRole(String key) {
        ProbeRoleMapper.ProbeRole role = new ProbeRoleMapper.ProbeRole();
        role.setRoleName("署名探针");
        role.setRoleKey(key);
        role.setStatus(0);
        role.setSort(0);
        return role;
    }

    /** 绕过 MyBatis 直接读回原始列，避免被任何映射逻辑掩盖。 */
    private Map<String, Object> rawRow(String roleKey) {
        return jdbcTemplate.queryForMap(
                "SELECT create_by, update_by, create_time, update_time, tenant_id "
                        + "FROM sys_role WHERE role_key = ?", roleKey);
    }

    @Test
    @DisplayName("无会话入口插入：create_by / update_by 必须是 NULL，绝不是 0")
    void noSessionLeavesAuditColumnsNull() {
        context.asNoSession();

        // 这正是三类无会话入口的形态：租户由数据显式携带，操作人根本不存在（契约 §2.8）
        ProbeRoleMapper.ProbeRole role = newRole("probe_audit_nosession");
        TenantHelper.runWithTenant(TENANT_A, () -> roleMapper.insert(role));

        Map<String, Object> row = rawRow("probe_audit_nosession");
        assertThat(row.get("create_by"))
                .as("填 0 会造出一条「用户 0 创建的」假记录，而 0 是平台根节点的真实 ID —— "
                        + "契约 §2.2 同源原则：不要让「没发生」和「发生过又被抹掉」落在同一取值上")
                .isNull();
        assertThat(row.get("update_by")).isNull();
        assertThat(row.get("tenant_id"))
                .as("租户仍由插件按 runWithTenant 的显式上下文注入")
                .isEqualTo(TENANT_A);
    }

    @Test
    @DisplayName("有会话时插入：create_by 与 update_by 都填当前操作人")
    void sessionFillsBothOnInsert() {
        context.asTenantUser(TENANT_A, OPERATOR_ID, OPERATOR_NODE);

        roleMapper.insert(newRole("probe_audit_insert"));

        Map<String, Object> row = rawRow("probe_audit_insert");
        assertThat(row.get("create_by")).isEqualTo(OPERATOR_ID);
        assertThat(row.get("update_by")).isEqualTo(OPERATOR_ID);
    }

    @Test
    @DisplayName("更新只改 update_by，create_by 保持首次署名不变")
    void updateOnlyTouchesUpdateBy() {
        context.asTenantUser(TENANT_A, OPERATOR_ID, OPERATOR_NODE);
        ProbeRoleMapper.ProbeRole role = newRole("probe_audit_update");
        roleMapper.insert(role);

        long anotherOperator = 1953827104412590103L;
        context.asTenantUser(TENANT_A, anotherOperator, OPERATOR_NODE);
        role.setRoleName("改过名");
        roleMapper.updateById(role);

        Map<String, Object> row = rawRow("probe_audit_update");
        assertThat(row.get("create_by"))
                .as("署名一律用 create_by，且创建人不随后续修改而变")
                .isEqualTo(OPERATOR_ID);
        assertThat(row.get("update_by"))
                .as("这里刻意【复用】插入时那个实体对象 —— 它的 updateBy 上带着上一次修改人。"
                        + "MyBatis-Plus 的 strictUpdateFill 只在字段为 null 时才填，"
                        + "会让本次操作人被静默丢弃、记录上写着「最后修改人是别人」。"
                        + "AuditFieldHandler 因此改用 setFieldValByName 无条件覆盖")
                .isEqualTo(anotherOperator);
    }

    @Test
    @DisplayName("create_time / update_time 由数据库赋值，Java 侧不填")
    void timestampsComeFromDatabase() {
        context.asTenantUser(TENANT_A, OPERATOR_ID, OPERATOR_NODE);

        ProbeRoleMapper.ProbeRole role = newRole("probe_audit_time");
        roleMapper.insert(role);

        assertThat(role.getCreateTime())
                .as("Java 侧不填时间：两侧都填会把应用时钟与数据库时钟的差异静默写进数据，"
                        + "而契约 §6「服务器、数据库、接口三层都在东八区」防的正是这件事")
                .isNull();

        Map<String, Object> row = rawRow("probe_audit_time");
        assertThat(row.get("create_time")).as("由 DEFAULT CURRENT_TIMESTAMP 赋值").isNotNull();
        assertThat(row.get("update_time")).isNotNull();
    }
}
