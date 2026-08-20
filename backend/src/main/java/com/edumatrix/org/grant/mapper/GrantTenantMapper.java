package com.edumatrix.org.grant.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 巡检要遍历的租户清单。
 *
 * <p><b>{@code sys_tenant} 不带 {@code tenant_id} 列、压根不进租户插件</b>，
 * 所以这条查询<b>不需要任何逃生舱</b>（与 {@code AnonymizeArchivedStudentJob} 同口径）。
 * 「逐个进入每个租户」与「跨租户查询」形似而不同 —— 前者不占逃生舱清单。
 *
 * <p><b>必须放在 {@code mapper} 包下</b>：{@code @MapperScan} 只扫
 * {@code com.edumatrix.**.mapper}。放在 {@code job} 包里的表现是
 * <b>整个 Spring 上下文起不来</b>（{@code NoSuchBeanDefinitionException}）——
 * 这一条至少是响亮失败，不是静默的。
 *
 * <p>{@code status = 0} 启用：已停用 / 已到期的机构不必巡检，
 * 它们的授权本来就用不了（登录就被拦下）。
 */
@Mapper
public interface GrantTenantMapper {

    @Select("SELECT id FROM sys_tenant WHERE status = 0 AND deleted_at = 0 ORDER BY id")
    List<Long> selectActiveTenantIds();
}
