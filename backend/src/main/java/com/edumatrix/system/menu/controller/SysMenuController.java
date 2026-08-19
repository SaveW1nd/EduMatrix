package com.edumatrix.system.menu.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.R;
import com.edumatrix.system.menu.dto.MenuCreateReq;
import com.edumatrix.system.menu.dto.MenuUpdateReq;
import com.edumatrix.system.menu.service.SysMenuService;
import com.edumatrix.system.menu.vo.MenuCreatedVO;
import com.edumatrix.system.menu.vo.MenuTreeVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 菜单管理接口（03-01 §4.1~§4.4，四个）。
 *
 * <h2>权限收敛：只有 §4.1 对 org_admin 开放</h2>
 * <p>§4.2/§4.3/§4.4 是<b>平台级维护接口</b>，「org_admin 及以下调用返回 403」（§4.2 原文）。
 * 这一条<b>不靠代码里的 if 判断</b>，靠 {@code sys_role_menu} 的初始化数据：
 * {@code system:menu:add/edit/remove} 三个标识只绑了 {@code super_admin}，
 * 于是 {@code @SaCheckPermission} 对 org_admin 天然 403。
 *
 * <p><b>为什么这比写 if 好</b>：权限的真相只有一份（菜单绑定数据），
 * 代码里再写一份 {@code if (!isSuperAdmin()) throw forbidden()} 就有了两份，
 * 而两份迟早会分叉 —— 04 §B 规则 2 与分册的那处冲突正是同一个形态。
 */
@RestController
@RequestMapping("/api/v1/system/menus")
public class SysMenuController {

    private final SysMenuService sysMenuService;

    public SysMenuController(SysMenuService sysMenuService) {
        this.sysMenuService = sysMenuService;
    }

    /**
     * §4.1 查询菜单树。{@code super_admin} 全量；{@code org_admin} 仅自身权限范围内
     * （用于分配菜单时展示，防提权）。
     */
    @GetMapping("/tree")
    @SaCheckPermission("system:menu:list")
    public R<List<MenuTreeVO>> tree(@RequestParam(required = false) String menuName,
                                    @RequestParam(required = false) Integer visible) {
        return R.ok(sysMenuService.tree(menuName, visible));
    }

    /** §4.2 创建菜单。仅 {@code super_admin}。 */
    @PostMapping
    @SaCheckPermission("system:menu:add")
    @OperLog(module = "菜单管理", action = "创建菜单")
    public R<MenuCreatedVO> create(@Valid @RequestBody MenuCreateReq req) {
        return R.ok(sysMenuService.create(req));
    }

    /** §4.3 修改菜单。仅 {@code super_admin}；{@code menuType} 创建后不可修改。 */
    @PutMapping("/{id}")
    @SaCheckPermission("system:menu:edit")
    @OperLog(module = "菜单管理", action = "修改菜单")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody MenuUpdateReq req) {
        sysMenuService.update(id, req);
        return R.ok();
    }

    /** §4.4 删除菜单（逻辑删除）。仅 {@code super_admin}；有子节点或被角色引用 → {@code 10009}。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:menu:remove")
    @OperLog(module = "菜单管理", action = "删除菜单")
    public R<Void> delete(@PathVariable("id") Long id) {
        sysMenuService.delete(id);
        return R.ok();
    }
}
