package com.edumatrix.system.tenant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.tenant.entity.SysTenantConfig;

/**
 * {@code sys_tenant_config} 的读写（03-01 §6）。
 *
 * <p>本表带 {@code tenant_id} 且<b>不在放行清单里</b>（契约 §2.9 只放行
 * {@code sys_role} / {@code sys_role_menu}），故 §6.1 / §6.2 的租户条件由插件注入，
 * <b>业务代码一个字不写</b>——那两个接口只对 {@code org_admin} 开放，会话租户就是本租户。
 */
@Mapper
public interface SysTenantConfigMapper extends BaseMapper<SysTenantConfig> {

    /**
     * 按租户 + 键点查配置值（{@code uk_tenant_config_key} 命中）。
     *
     * <h2>为什么这一条要显式写 {@code tenant_id}，而 §6.1/§6.2 不写</h2>
     * <p>它服务的是跨领域的 {@code TenantConfigHelper}（模块 12/13 调用），
     * 调用场景包括<b>超管会话</b>——而超管会话下租户插件走的是"整体放行"通道
     * （{@code ignoreTable} 返回 true），<b>不注入任何 {@code tenant_id} 条件</b>：
     * 不显式写就会把<b>全平台每一个租户</b>的该键行都查出来，然后随机取一行。
     * 显式条件在插件也注入时是同值重复，无害；在插件放行时是唯一的那道过滤。
     *
     * <p>写的是<b>等值条件</b>，不是 {@code OR tenant_id = 0}
     * （{@code check_backend_conventions.sh} 检查①grep 的是后者）。
     */
    @Select("SELECT config_value FROM sys_tenant_config "
            + "WHERE tenant_id = #{tenantId} AND config_key = #{configKey} AND deleted_at = 0 LIMIT 1")
    String selectValue(@Param("tenantId") Long tenantId, @Param("configKey") String configKey);
}
