package com.edumatrix.org.grant.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.response.R;
import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.org.grant.dto.GrantCreateReq;
import com.edumatrix.org.grant.dto.GrantRevokeReq;
import com.edumatrix.org.grant.dto.GrantableResourceQueryReq;
import com.edumatrix.org.grant.dto.NodeGrantedResourceQueryReq;
import com.edumatrix.org.grant.service.GrantQueryService;
import com.edumatrix.org.grant.service.GrantRevokeService;
import com.edumatrix.org.grant.service.GrantWriteService;
import com.edumatrix.org.grant.vo.GrantCreatedVO;
import com.edumatrix.org.grant.vo.GrantRevokedVO;
import com.edumatrix.org.grant.vo.GrantableResourceVO;
import com.edumatrix.org.grant.vo.NodeGrantedResourceVO;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.validation.Valid;

/**
 * 模块 11 · 资源授权（03-02 §9）。本类先落读侧两个接口，写侧在后续提交接上。
 *
 * <h2>⚠ {@code @SaCheckPermission} 通过 ≠ 有权授权</h2>
 * <p>注解只管<b>功能权限</b>那一维（{@code sys_role_menu} → {@code sys_menu.perms}），
 * 它<b>不随树收缩</b>、也<b>不说明你能操作哪些资源</b>。
 * 拥有性一律在 Service 里走 {@code ResourceOwnerChecker.canRegrant}，
 * <b>不得因为注解已放行就跳过</b>（04 §B 模块 11 规则 18）。
 * 权限标识的真相在 {@code sys_role_menu} 的初始化数据里，代码里不写第二份。
 */
@RestController
@RequestMapping("/api/v1/org")
public class OrgGrantController {

    private final GrantQueryService grantQueryService;
    private final GrantWriteService grantWriteService;
    private final GrantRevokeService grantRevokeService;

    public OrgGrantController(GrantQueryService grantQueryService,
                              GrantWriteService grantWriteService,
                              GrantRevokeService grantRevokeService) {
        this.grantQueryService = grantQueryService;
        this.grantWriteService = grantWriteService;
        this.grantRevokeService = grantRevokeService;
    }

    /**
     * 接口 37 §9.1 我可授权的资源列表。{@code org_admin} / {@code teacher}。
     *
     * <p>返回的是<b>接口 38 的合法资源全集</b>：列表之外的任何资源 ID 传给接口 38
     * 一律 {@code 10301}。受授权那一半已按 {@code canRegrant} 滤掉跨管辖行 ——
     * 否则「列表里看得见、授出去报 10301」，界面在骗人。
     */
    @GetMapping("/grants/grantable-resources")
    @SaCheckPermission("org:grant:list")
    public R<PageResult<GrantableResourceVO>> grantableResources(
            @Valid GrantableResourceQueryReq req) {
        return R.ok(grantQueryService.grantableResources(req));
    }

    /**
     * 接口 38 §9.2 授权资源给节点。{@code org_admin}；{@code teacher}（目标限名下学员）。
     *
     * <p><b>注解只放行了「下发」这个动作</b>，不说明能下发哪些资源 ——
     * 拥有性在 {@code GrantWriteService} 里走 {@code canRegrant}，不满足 {@code 10301}，
     * 且响应<b>不区分「资源不存在」与「你无权」</b>（契约 §2.5 规则 1 防探测）。
     *
     * <p>全部授权写 {@code sys_oper_log}（PRD FR-1 规则 9）。
     */
    @PostMapping("/grants")
    @SaCheckPermission("org:grant:grant")
    @OperLog(module = "资源授权", action = "授权资源给节点")
    public R<GrantCreatedVO> grant(@Valid @RequestBody GrantCreateReq req) {
        return R.ok(grantWriteService.grant(req));
    }

    /**
     * 接口 39 §9.3 撤销资源授权（<b>级联子树</b>）。
     * {@code org_admin}；{@code teacher}（仅可撤销自己授给名下学员的授权）。
     *
     * <p><b>{@code DELETE} 携带 JSON 请求体</b>：批量撤销无法用路径参数表达（§9.3 说明段）。
     *
     * <p><b>级联是强制行为，请求体里没有关闭开关</b>（§9.3、契约 §2.5 规则 5）。
     * 响应通过 {@code cascadeDetail} 完整披露影响面，供操作者确认。
     *
     * <p>⚠ {@code sys_oper_log} 这一行目前<b>只含入参</b>（切面序列化的是参数、不是返回值），
     * 而 04 §B 规则 17 / PRD FR-4 规则 7 要求它<b>含级联影响的节点数与学员数</b> ——
     * 那两个数字现在只在<b>响应</b>里。缺口已登记，修它要动模块 05 的公共切面，
     * 不由本模块单方面决定。
     */
    @DeleteMapping("/grants")
    @SaCheckPermission("org:grant:revoke")
    @OperLog(module = "资源授权", action = "撤销资源授权（级联子树）")
    public R<GrantRevokedVO> revoke(@Valid @RequestBody GrantRevokeReq req) {
        return R.ok(grantRevokeService.revokeCascade(req));
    }

    /**
     * 接口 41 §9.5 节点已获授权资源列表。
     * {@code org_admin}；{@code teacher}（自身与名下学员）；{@code student}（仅自身）。
     *
     * <h2>为什么是 {@code @SaCheckOr} —— 照接口 26 的先例</h2>
     * <p>契约 §3.1 边界 0：<b>学生端接口一律不加 {@code @SaCheckPermission}，因而不发
     * {@code perms}</b>，{@code student} 在 {@code sys_role_menu} 里一行绑定都没有。
     * 单个 {@code @SaCheckPermission("org:grant:list")} 会让学生拿 403，
     * 完全不加又会让管理员与教师<b>失去 perms 这道门</b>。
     * {@code @SaCheckOr} 让两侧各按各的判据，与 {@code OrgStudentController#changeLogs}
     *（接口 26 学生异动轨迹）<b>完全同型</b>，不另造一套。
     *
     * <p>「仅本人 / 仅名下」那一半由 Service 的子树判定承担：
     * 学生的子树就是他自己，<b>同一个判定覆盖三种角色</b>，越界 404（契约 §2.4 三分法：
     * 路径上的操作对象越界不暴露存在性）。
     */
    @GetMapping("/nodes/{id}/granted-resources")
    @SaCheckOr(
            permission = @SaCheckPermission("org:grant:list"),
            role = @SaCheckRole("student"))
    public R<PageResult<NodeGrantedResourceVO>> nodeGrantedResources(
            @PathVariable("id") Long id, @Valid NodeGrantedResourceQueryReq req) {
        return R.ok(grantQueryService.nodeGrantedResources(id, req));
    }
}
