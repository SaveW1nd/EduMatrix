package com.edumatrix.org.member.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.response.R;
import com.edumatrix.org.member.dto.AdminCreateReq;
import com.edumatrix.org.member.dto.AdminPageQuery;
import com.edumatrix.org.member.dto.AdminUpdateReq;
import com.edumatrix.org.member.service.OrgAdminService;
import com.edumatrix.org.member.vo.AdminVO;
import com.edumatrix.org.member.vo.MemberCreatedVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 管理员管理接口（03-02 §4.1~§4.4，接口 7 / 8 / 9 / 10）。
 *
 * <p><b>本节路径 {@code {id}} 是管理员<b>节点 ID</b></b>（§4.1 字段说明：
 * 「{@code nodeId} 管理员节点 ID（{@code org_node.id}），<b>本节其余接口的 {@code {id}}</b>」）——
 * 与教师、学生两节相反（那两节用档案 ID）。三节导语各写了一句。
 *
 * <h2>角色收敛靠菜单绑定数据，不靠这里的 if</h2>
 * <p>与 {@code OrgNodeController} / {@code SysUserController} 立的规矩一致：
 * <b>权限的真相只有一份</b>，在 {@code sys_role_menu} 的初始化数据里。
 *
 * <p><b>{@code org:staff:list} 同时管着接口 7 与接口 11</b>（契约 §10 附表 A 的
 * 「人员管理」是一个页面级 {@code :list}，按 §3.1 边界 2 两个列表都挂在它下面），
 * 绑给 {@code super_admin} / {@code org_admin}。这与 §5.1 写的「教师可查本人一条」冲突，
 * <b>已登记为 04-实施计划.md §E 的 F-30</b>：那不是「哪一侧写错了」，
 * 是 perms 粒度不足以表达分册要的角色集，只能由需方在「加一个 perms」与「订正分册」之间选。
 * 实现按契约（权威），教师调接口 11 返回 403。
 *
 * <h2>三个写接口都标了 {@code @OperLog}</h2>
 * <p>PRD F1-2 规则 13：「所有结构变更（建/移/停/删）写 {@code sys_oper_log}」。
 * <b>切面是模块 05 的交付物、本轮被跳过</b>（F-25），在它到位之前注解不产生任何行为，
 * <b>但已标注的位置一个都不用改</b>。
 *
 * <p>本模块另有<b>两处显式写 {@code sys_oper_log}</b>（创建学生的监护人同意留痕、
 * 脱敏任务），那是<b>合规留痕</b>不是操作日志，与切面将来写的行 {@code action} 不同、
 * 可永久共存 —— 理由逐条见 {@code MemberOperLogWriter} 的类注释。
 */
@RestController
@RequestMapping("/api/v1/org/admins")
public class OrgAdminController {

    private final OrgAdminService adminService;

    public OrgAdminController(OrgAdminService adminService) {
        this.adminService = adminService;
    }

    /** 接口 7 §4.1 管理员分页列表。仅 {@code org_admin}（超管亦绑定）。 */
    @GetMapping
    @SaCheckPermission("org:staff:list")
    public R<PageResult<AdminVO>> page(AdminPageQuery query) {
        return R.ok(adminService.page(query));
    }

    /**
     * 接口 8 §4.2 新建下级管理员。仅 {@code org_admin}。
     *
     * <p><b>{@code saveParams = false}</b>：请求体里可能有 {@code initPassword} 明文，
     * 写进日志等于把它落了库，与 PRD §7.3「明文永不落库」直接冲突 ——
     * 与模块 06 的 §3.6 重置密码取同一处置。
     */
    @PostMapping
    @SaCheckPermission("org:admin:add")
    @OperLog(module = "人员管理", action = "新建下级管理员", saveParams = false)
    public R<MemberCreatedVO> create(@Valid @RequestBody AdminCreateReq req) {
        return R.ok(adminService.create(req));
    }

    /** 接口 9 §4.3 修改管理员。仅 {@code org_admin}；不得改自己 → {@code 10012}。 */
    @PutMapping("/{id}")
    @SaCheckPermission("org:admin:edit")
    @OperLog(module = "人员管理", action = "修改管理员")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody AdminUpdateReq req) {
        adminService.update(id, req);
        return R.ok();
    }

    /** 接口 10 §4.4 删除管理员。节点下有任何子节点 → {@code 10108}。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("org:admin:remove")
    @OperLog(module = "人员管理", action = "删除管理员")
    public R<Void> delete(@PathVariable("id") Long id) {
        adminService.delete(id);
        return R.ok();
    }
}
