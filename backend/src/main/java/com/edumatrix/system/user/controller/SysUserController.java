package com.edumatrix.system.user.controller;

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
import com.edumatrix.system.user.dto.UserCreateReq;
import com.edumatrix.system.user.dto.UserPageQuery;
import com.edumatrix.system.user.dto.UserPasswordResetReq;
import com.edumatrix.system.user.dto.UserStatusReq;
import com.edumatrix.system.user.dto.UserUpdateReq;
import com.edumatrix.system.user.service.SysUserService;
import com.edumatrix.system.user.vo.PasswordResetVO;
import com.edumatrix.system.user.vo.UserCreatedVO;
import com.edumatrix.system.user.vo.UserListVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 用户管理接口（03-01 §2.1~§2.6，六个）。
 *
 * <h2>写接口（§2.2~§2.6）一律仅 {@code super_admin}</h2>
 * <p>§2 导语原文：本组写接口「用途限定为平台级账号维护」，
 * <b>机构侧人员的建、改、删、重置密码、启停用一律走 {@code /api/v1/org/**}</b>；
 * 只有 §2.1 分页查询保留 {@code org_admin}。
 *
 * <p><b>注意：这与 04-实施计划.md §B 模块 03 的规则 2 不一致。</b>那一条只写了
 * 「{@code PUT /users/{id}/status} 仅超管可调」，漏了 §2.2~§2.5 四条。
 * 三方证据都指向分册：① 分册 §2.2~§2.6 的「允许角色」栏逐条写着「仅 super_admin」；
 * ② 菜单初始化数据 {@code V202608140000} 里 {@code system:user:add/edit/remove/resetPwd/status}
 * 五个标识<b>只绑了 super_admin</b>；③ §2 导语给出了理由（孤儿数据）。
 * 按权威顺序（分册 &gt; {@code 04} 实施计划）实现，{@code 04 §B} 的订正另行处理。
 *
 * <p>收敛<b>不靠这里的 if</b>，靠上述菜单绑定数据 —— 权限的真相只有一份。
 * 代码里再写一份就有了两份，而两份迟早会分叉，那正是上面这处冲突的形态。
 */
@RestController
@RequestMapping("/api/v1/system/users")
public class SysUserController {

    private final SysUserService sysUserService;

    public SysUserController(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    /**
     * §2.1 分页查询用户。{@code super_admin} + {@code org_admin}。
     *
     * <p>数据权限：org_admin 仅自身节点子树内的账号；super_admin 可传 {@code tenantId}
     * 查指定租户，不传则查平台级账号。teacher / student 无此 {@code perms}，天然 403。
     */
    @GetMapping
    @SaCheckPermission("system:user:list")
    public R<PageResult<UserListVO>> page(UserPageQuery query) {
        return R.ok(sysUserService.page(query));
    }

    /**
     * §2.2 创建用户。<b>仅 {@code super_admin}</b>。
     *
     * <p>副作用：同一事务内创建 {@code org_node} 节点（挂在 {@code parentNodeId} 下）、
     * 回写 {@code sys_user.node_id}、记一条 {@code org_node_change_log}（{@code change_type=1}）。
     */
    @PostMapping
    @SaCheckPermission("system:user:add")
    @OperLog(module = "用户管理", action = "创建用户", saveParams = false)
    public R<UserCreatedVO> create(@Valid @RequestBody UserCreateReq req) {
        return R.ok(sysUserService.create(req));
    }

    /** §2.3 修改用户。<b>仅 {@code super_admin}</b>；{@code username}/{@code userType}/{@code nodeId} 不可改。 */
    @PutMapping("/{id}")
    @SaCheckPermission("system:user:edit")
    @OperLog(module = "用户管理", action = "修改用户")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody UserUpdateReq req) {
        sysUserService.update(id, req);
        return R.ok();
    }

    /**
     * §2.4 删除用户（逻辑删除）。<b>仅 {@code super_admin}</b>。
     *
     * <p>同时逻辑删除其 {@code org_node} 节点并作废在线 Token；
     * 该节点下若仍有未删除的子节点则拒绝（{@code 10108}）。
     * 对已删除用户重复调用同样返回 200（幂等）。
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:user:remove")
    @OperLog(module = "用户管理", action = "删除用户")
    public R<Void> delete(@PathVariable("id") Long id) {
        sysUserService.delete(id);
        return R.ok();
    }

    /**
     * §2.5 重置用户密码。<b>仅 {@code super_admin}</b>；重置后该用户全部在线会话强制下线。
     *
     * <p>两个分支的响应形状不同，这是文档自洽的两支（见 {@code UserPasswordResetReq} 注释）：
     * 传了 {@code newPassword} → {@code data: null}；不传 → {@code data.initialPassword}
     * （明文仅此一次，不落库、不可再查）。
     */
    @PutMapping("/{id}/password/reset")
    @SaCheckPermission("system:user:resetPwd")
    @OperLog(module = "用户管理", action = "重置密码", saveParams = false)
    public R<PasswordResetVO> resetPassword(@PathVariable("id") Long id,
                                            @Valid @RequestBody UserPasswordResetReq req) {
        PasswordResetVO vo = sysUserService.resetPassword(id, req);
        return R.ok("密码已重置，该用户需重新登录", vo);
    }

    /**
     * §2.6 启用/停用用户。<b>仅 {@code super_admin}</b>（契约 §2.3 已收敛）。
     *
     * <p>操作的是 {@code sys_user.status}，即<b>与组织无关的账号级封禁</b>。
     * 机构侧的停用走 02-组织机构分册接口 5（节点停用/启用），两者不联动。
     */
    @PutMapping("/{id}/status")
    @SaCheckPermission("system:user:status")
    @OperLog(module = "用户管理", action = "启用/停用用户")
    public R<Void> changeStatus(@PathVariable("id") Long id,
                                @Valid @RequestBody UserStatusReq req) {
        sysUserService.changeStatus(id, req);
        return R.ok();
    }
}
