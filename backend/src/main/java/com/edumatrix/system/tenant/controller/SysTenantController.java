package com.edumatrix.system.tenant.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.R;
import com.edumatrix.system.tenant.dto.TenantCreateReq;
import com.edumatrix.system.tenant.dto.TenantPageQuery;
import com.edumatrix.system.tenant.dto.TenantRenewReq;
import com.edumatrix.system.tenant.dto.TenantStatusReq;
import com.edumatrix.system.tenant.dto.TenantUpdateReq;
import com.edumatrix.system.tenant.service.SysTenantService;
import com.edumatrix.system.tenant.service.TenantProvisionService;
import com.edumatrix.system.tenant.vo.TenantCreatedVO;
import com.edumatrix.system.tenant.vo.TenantDetailVO;
import com.edumatrix.system.tenant.vo.TenantListVO;
import com.edumatrix.system.tenant.vo.TenantRenewedVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 租户管理接口（03-01 §5.1~§5.7，七个）。
 *
 * <h2>七个接口全部仅 {@code super_admin}，且不做租户注入</h2>
 * <p>§5 导语逐字：「平台超管（{@code super_admin}）专用，其余角色（含 {@code org_admin}）
 * 调用一律返回 403。租户即机构（{@code sys_tenant}），{@code tenant_id} 即本表 {@code id}。
 * 本组接口为平台级操作，不做租户注入」。
 *
 * <p>403 <b>不靠本层的 if</b>：{@code system:tenant:*} 七个权限标识在
 * {@code V202608140000__init_menu_and_role_menu.sql} 里只绑了 {@code super_admin}，
 * {@code @SaCheckPermission} 对 {@code org_admin} 天然 403 —— 与 §2 用户管理写接口同一手法。
 *
 * <h2>开通（§5.3）单独一个 Service</h2>
 * <p>它是三步同事务的循环依赖解法（契约 §2.1），与其余六个"改一行租户数据"的接口
 * 不是一类东西，理由见 {@link TenantProvisionService} 的类注释。
 */
@RestController
@RequestMapping("/api/v1/system/tenants")
public class SysTenantController {

    private final SysTenantService sysTenantService;
    private final TenantProvisionService tenantProvisionService;

    public SysTenantController(SysTenantService sysTenantService,
                               TenantProvisionService tenantProvisionService) {
        this.sysTenantService = sysTenantService;
        this.tenantProvisionService = tenantProvisionService;
    }

    /** §5.1 分页查询租户。{@code expireBefore} 用于筛选临期租户。 */
    @GetMapping
    @SaCheckPermission("system:tenant:list")
    public R<PageResult<TenantListVO>> page(TenantPageQuery query) {
        return R.ok(sysTenantService.page(query));
    }

    /** §5.2 查询租户详情。{@code id} / {@code rootNodeId} / {@code adminNodeId} 恒为同一个值。 */
    @GetMapping("/{id}")
    @SaCheckPermission("system:tenant:query")
    public R<TenantDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(sysTenantService.detail(id));
    }

    /**
     * §5.3 创建租户（开通机构）——<b>三步同一事务</b>，任一步失败整体回滚。
     *
     * <p>响应里的 {@code initialPassword} <b>仅此一次</b>：服务端只存 BCrypt 散列，
     * 明文不落库不可再查（PRD F1-1 规则 6）。
     */
    @PostMapping
    @SaCheckPermission("system:tenant:add")
    @OperLog(module = "租户管理", action = "开通机构", saveParams = false)
    public R<TenantCreatedVO> create(@Valid @RequestBody TenantCreateReq req) {
        return R.ok(tenantProvisionService.create(req));
    }

    /** §5.4 修改租户。改机构名同步刷机构根节点的 {@code node_name}；不改到期时间与状态。 */
    @PutMapping("/{id}")
    @SaCheckPermission("system:tenant:edit")
    @OperLog(module = "租户管理", action = "修改租户")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody TenantUpdateReq req) {
        sysTenantService.update(id, req);
        return R.ok();
    }

    /** §5.5 删除租户（逻辑删除）。仅允许删已停用的租户；机构根节点及整棵子树一并逻辑删除。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:tenant:remove")
    @OperLog(module = "租户管理", action = "删除租户")
    public R<Void> delete(@PathVariable("id") Long id) {
        sysTenantService.delete(id);
        return R.ok();
    }

    /** §5.6 租户续期。响应 {@code msg} 是「续期成功」——§5.6 的响应示例逐字如此。 */
    @PutMapping("/{id}/renew")
    @SaCheckPermission("system:tenant:renew")
    @OperLog(module = "租户管理", action = "租户续期")
    public R<TenantRenewedVO> renew(@PathVariable("id") Long id,
                                    @Valid @RequestBody TenantRenewReq req) {
        return R.ok("续期成功", sysTenantService.renew(id, req));
    }

    /** §5.7 启用/停用租户。停用后该租户全员在线 Token 作废，登录返回 {@code 10007}。 */
    @PutMapping("/{id}/status")
    @SaCheckPermission("system:tenant:status")
    @OperLog(module = "租户管理", action = "启用/停用租户")
    public R<Void> changeStatus(@PathVariable("id") Long id,
                                @Valid @RequestBody TenantStatusReq req) {
        sysTenantService.changeStatus(id, req);
        return R.ok();
    }
}
