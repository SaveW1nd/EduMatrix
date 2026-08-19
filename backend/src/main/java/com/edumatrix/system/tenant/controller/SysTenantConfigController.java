package com.edumatrix.system.tenant.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.R;
import com.edumatrix.system.tenant.dto.TenantConfigUpdateReq;
import com.edumatrix.system.tenant.service.TenantConfigService;
import com.edumatrix.system.tenant.vo.TenantConfigItemVO;
import com.edumatrix.system.tenant.vo.TenantConfigUpdatedVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 租户配置接口（03-01 §6.1~§6.2，两个）。
 *
 * <h2>与 §5 的角色恰好相反：这两个只对 {@code org_admin} 开放</h2>
 * <p>§6.1 数据权限栏：租户配置是<b>机构级</b>配置，仅机构管理员可维护；
 * {@code teacher} / {@code student} 及 <b>{@code super_admin}（平台级无租户上下文）</b>
 * 调用返回 403。同样由初始化数据实现——{@code system:tenantConfig:list} / {@code :edit}
 * 只绑了 {@code org_admin}。
 *
 * <p>所以一个租户的配置<b>连超管也改不了</b>，这是设计意图不是遗漏：超管没有租户上下文，
 * 他"以哪个租户的身份"写这一行是无法回答的问题（{@code sys_tenant_config.tenant_id}
 * 是 {@code NOT NULL}）。平台要统一调整的东西属于<b>平台默认值</b>，改的是
 * {@code common/tenantconfig/TenantConfigKey}（发版），不是某个租户的行。
 */
@RestController
@RequestMapping("/api/v1/system/tenant-configs")
public class SysTenantConfigController {

    private final TenantConfigService tenantConfigService;

    public SysTenantConfigController(TenantConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    /**
     * §6.1 查询租户配置列表。<b>非分页</b>，固定返回键白名单内全部配置项
     * （含本租户未自定义过的键，此时 {@code isDefault = true}、{@code updateTime = null}），
     * 按 {@code configKey} 升序。
     */
    @GetMapping
    @SaCheckPermission("system:tenantConfig:list")
    public R<List<TenantConfigItemVO>> list() {
        return R.ok(tenantConfigService.list());
    }

    /**
     * §6.2 修改租户配置。键不在白名单 → {@code 10016}；值类型不符或超范围 → 400。
     *
     * <p>PUT 幂等：命中 {@code uk_tenant_config_key(tenant_id, config_key, deleted_at)} 则更新。
     */
    @PutMapping("/{configKey}")
    @SaCheckPermission("system:tenantConfig:edit")
    @OperLog(module = "租户配置", action = "修改租户配置")
    public R<TenantConfigUpdatedVO> update(@PathVariable("configKey") String configKey,
                                           @Valid @RequestBody TenantConfigUpdateReq req) {
        return R.ok(tenantConfigService.update(configKey, req));
    }
}
