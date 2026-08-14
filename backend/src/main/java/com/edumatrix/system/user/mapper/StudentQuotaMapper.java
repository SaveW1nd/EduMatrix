package com.edumatrix.system.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 学生上限校验的两条只读查询（03-01 §2.2：「创建学生账号受租户 {@code max_student_count} 限制」→ {@code 10207}）。
 *
 * <h2>口径定案：按 {@code org_student} 的<b>在读</b>行数计，与模块 07 同口径</h2>
 * <p>00-通用约定 §9.2 给 {@code 10207} 的触发场景是「<b>在读</b>学生总数将超过
 * {@code sys_tenant.max_student_count}」，而「在读」的定义是 {@code org_student.status = 0}。
 * 另一个候选口径（按 {@code org_node} 里 {@code node_type = 3} 的节点数）被否决：
 * <b>同一个上限两套算法</b>，模块 07 上线后同一个租户会算出两个不同的学生数，这种分叉查起来极难。
 *
 * <h2>本接口实际不可达 {@code 10207}，这是已登记的已知后果</h2>
 * <p>§2.2 这条路径<b>不写 {@code org_student} 档案</b>（那正是它被禁止给 org_admin 用的理由 ——
 * 会产生 PRD F1-3 规则 1 明令禁止的孤儿数据）。于是经它建出的学生<b>没有 {@code org_student} 行
 * → 永远不是「在读」→ 既不占额度、也不会把额度撑爆</b>，本校验因此恒不触发。
 *
 * <p><b>那为什么还要写它</b>：§2.2 的数据权限栏与错误码表里都有这一条，实现方不得擅自删；
 * 而一旦将来 §2.2 被改成"也写档案"（或 F-22 定案禁止经此路径建学生），
 * 这段代码是唯一一处不需要重新发明的口径。
 *
 * <p><b>已登记为 04-实施计划.md §E 的 F-22</b>：是否应禁止经 §2.2 创建 {@code userType=3}。
 * 卡上线前，不卡任何模块。
 *
 * <h2>⚠ 临时构件</h2>
 * <p>{@code org_student} 归 {@code org} 领域（模块 07）、{@code sys_tenant} 归
 * {@code system/tenant}（模块 04）。两者都还不存在，故在此开两条窄只读查询，
 * 与 {@code SystemOrgNodeMapper} 同批交接：对方领域建成后本类删除，改调其 Service。
 */
@Mapper
public interface StudentQuotaMapper {

    /**
     * 租户可容纳的在读学生上限；租户不存在时返回 {@code null}。
     *
     * <p>{@code sys_tenant} <b>没有 {@code tenant_id} 列</b>，是全库仅有的两张纯平台级表之一
     * （契约 §2.9），租户处理器的 {@code ignoreTable} 对它返回 true —— 它压根不进插件，
     * 所以这里必须显式按 {@code id} 定位，且这<b>不是</b>一次跨租户越权读。
     */
    @Select("SELECT max_student_count FROM sys_tenant WHERE id = #{tenantId} AND deleted_at = 0")
    Integer selectMaxStudentCount(@Param("tenantId") Long tenantId);

    /**
     * 该租户当前在读（{@code org_student.status = 0}）学生数。
     *
     * <p>{@code org_student} 带 {@code tenant_id} 且不在放行清单里，条件由租户插件注入 ——
     * <b>这里一个字不写</b>。超管会话下插件整体放行，故调用方必须用
     * {@code TenantHelper.runWithTenant(目标租户, ...)} 包住（契约 §2.8 规则 1：从数据显式取），
     * 否则超管建号时数出来的是<b>全平台</b>的在读学生数。
     */
    @Select("SELECT COUNT(1) FROM org_student WHERE status = 0 AND deleted_at = 0")
    long countActiveStudents();
}
