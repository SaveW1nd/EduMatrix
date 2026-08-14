package com.edumatrix.system.role.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.response.R;
import com.edumatrix.system.role.dto.RoleCreateReq;
import com.edumatrix.system.role.dto.RoleMenuAssignReq;
import com.edumatrix.system.role.dto.RolePageQuery;
import com.edumatrix.system.role.dto.RoleUpdateReq;
import com.edumatrix.system.role.service.SysRoleService;
import com.edumatrix.system.role.vo.RoleCreatedVO;
import com.edumatrix.system.role.vo.RoleDetailVO;
import com.edumatrix.system.role.vo.RoleListVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 角色管理接口（03-01 §3.1~§3.6，六个）。
 *
 * <h2>六个接口都对 {@code org_admin} 开放，收紧在目标对象上</h2>
 * <p>这是本组与 §2 用户管理、§4 菜单管理的关键区别：那两组靠<b>接口级</b>收敛
 * （写接口只绑 super_admin，org_admin 直接 403）；本组六个标识
 * {@code system:role:list/query/add/edit/remove/assignMenu} 在菜单初始化数据里
 * <b>super_admin 与 org_admin 都绑了</b> —— 因为 org_admin 必须能管理本租户的自建角色。
 *
 * <p>所以收紧只能落在<b>目标对象</b>上：预置角色（{@code tenant_id = 0}）对 org_admin
 * 全只读、任何人不可删。这就是 {@code PresetRoleGuard} 存在的原因，
 * 也是 §3.4/§3.5/§3.6 返回 <b>400 而不是 403</b> 的原因 ——
 * 调用者有这个功能权限，只是这一行不可写。
 */
@RestController
@RequestMapping("/api/v1/system/roles")
public class SysRoleController {

    private final SysRoleService sysRoleService;

    public SysRoleController(SysRoleService sysRoleService) {
        this.sysRoleService = sysRoleService;
    }

    /**
     * §3.1 分页查询角色。{@code org_admin} 见「本租户角色 + 平台预置角色」，
     * {@code super_admin} 见全量 —— 两者都由租户插件实现，本层无过滤代码。
     */
    @GetMapping
    @SaCheckPermission("system:role:list")
    public R<PageResult<RoleListVO>> page(RolePageQuery query) {
        return R.ok(sysRoleService.page(query));
    }

    /** §3.2 查询角色详情（含 {@code menuIds} 回显）。跨租户返回 404，不暴露存在性。 */
    @GetMapping("/{id}")
    @SaCheckPermission("system:role:query")
    public R<RoleDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(sysRoleService.detail(id));
    }

    /** §3.3 创建角色。{@code roleKey} 不得使用预置值；{@code menuIds} 受防提权约束。 */
    @PostMapping
    @SaCheckPermission("system:role:add")
    public R<RoleCreatedVO> create(@Valid @RequestBody RoleCreateReq req) {
        return R.ok(sysRoleService.create(req));
    }

    /** §3.4 修改角色。预置角色对 {@code org_admin} 只读 → 400。 */
    @PutMapping("/{id}")
    @SaCheckPermission("system:role:edit")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody RoleUpdateReq req) {
        sysRoleService.update(id, req);
        return R.ok();
    }

    /** §3.5 删除角色（逻辑删除）。预置角色<b>任何人不可删</b> → 400；被引用 → {@code 10008}。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:role:remove")
    public R<Void> delete(@PathVariable("id") Long id) {
        sysRoleService.delete(id);
        return R.ok();
    }

    /** §3.6 为角色分配菜单（全量覆盖）。预置角色对 {@code org_admin} 只读 → 400；防提权 → 400。 */
    @PutMapping("/{id}/menus")
    @SaCheckPermission("system:role:assignMenu")
    public R<Void> assignMenus(@PathVariable("id") Long id,
                               @Valid @RequestBody RoleMenuAssignReq req) {
        sysRoleService.assignMenus(id, req);
        return R.ok();
    }
}
