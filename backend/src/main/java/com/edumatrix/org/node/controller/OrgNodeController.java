package com.edumatrix.org.node.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.R;
import com.edumatrix.org.node.dto.NodeMoveReq;
import com.edumatrix.org.node.dto.NodePasswordResetReq;
import com.edumatrix.org.node.dto.NodeStatusReq;
import com.edumatrix.org.node.dto.NodeTreeQuery;
import com.edumatrix.org.node.dto.NodeUpdateReq;
import com.edumatrix.org.node.service.NodeMoveOptions;
import com.edumatrix.org.node.service.NodeMoveService;
import com.edumatrix.org.node.service.NodePasswordResetService;
import com.edumatrix.org.node.service.NodeQueryService;
import com.edumatrix.org.node.service.NodeStatusService;
import com.edumatrix.org.node.service.NodeUpdateService;
import com.edumatrix.org.node.vo.NodeDetailVO;
import com.edumatrix.org.node.vo.NodeMovedVO;
import com.edumatrix.org.node.vo.NodePasswordResetVO;
import com.edumatrix.org.node.vo.NodeStatusChangedVO;
import com.edumatrix.org.node.vo.NodeTreeVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 组织树管理接口（03-02 §3.1~§3.6，六个）。
 *
 * <h2>角色收敛靠菜单绑定数据，不靠这里的 if</h2>
 * <p>与 {@code SysUserController} 立的规矩一致：<b>权限的真相只有一份</b>，
 * 在 {@code sys_role_menu} 的初始化数据里。代码里再写一份就有了两份，而两份迟早会分叉。
 * <ul>
 *   <li>{@code org:node:list}（§3.1 / §3.2）→ {@code super_admin} / {@code org_admin} /
 *       <b>{@code teacher}</b>。teacher 那条绑定是<b>本模块新增的迁移</b>补上的：
 *       基线 {@code V202608140000} 只绑了前两个，而 §3.1/§3.2 的权限栏都写着 teacher，
 *       §3.1 数据权限还专门写「教师调用时树根即其教师节点，返回的子树就是名下学员列表」——
 *       照基线实现，教师调这两个接口会拿 403；
 *   <li>{@code org:node:edit} / {@code :move} / {@code :status} → 仅 {@code org_admin}；
 *   <li>{@code org:node:resetPwd} → {@code org_admin} + {@code teacher}（teacher 仅限
 *       名下学员，由子树判定天然承担，见 {@code NodePasswordResetService} 类注释）。
 * </ul>
 *
 * <h2>四个写接口都标了 {@code @OperLog}</h2>
 * <p>PRD F1-2 规则 13：「所有结构变更（建/移/停/删）写 {@code sys_oper_log}」；
 * 04-实施计划.md 模块 06 规则 12 对重置密码也有同一句。
 * <b>写 {@code sys_oper_log} 的切面是模块 05 的交付物</b>（{@code OperLog} 类注释：
 * 「注解定义在模块 01，切面实现在模块 05……在切面到位之前，本注解不产生任何行为，
 * 但已标注的位置一个都不用改」），而模块 05 本轮被跳过 ——
 * <b>已登记为 04-实施计划.md §E 的 F-25</b>。标注解是现在唯一该做的事：
 * 等切面到位，这四处自动生效，一行都不用改。
 */
@RestController
@RequestMapping("/api/v1/org/nodes")
public class OrgNodeController {

    private final NodeQueryService nodeQueryService;
    private final NodeUpdateService nodeUpdateService;
    private final NodeMoveService nodeMoveService;
    private final NodeStatusService nodeStatusService;
    private final NodePasswordResetService nodePasswordResetService;

    public OrgNodeController(NodeQueryService nodeQueryService,
                             NodeUpdateService nodeUpdateService,
                             NodeMoveService nodeMoveService,
                             NodeStatusService nodeStatusService,
                             NodePasswordResetService nodePasswordResetService) {
        this.nodeQueryService = nodeQueryService;
        this.nodeUpdateService = nodeUpdateService;
        this.nodeMoveService = nodeMoveService;
        this.nodeStatusService = nodeStatusService;
        this.nodePasswordResetService = nodePasswordResetService;
    }

    /**
     * §3.1 组织树查询。{@code org_admin} / {@code teacher} / {@code super_admin}。
     *
     * <p><b>默认懒加载</b>：不传 {@code parentId} 只返回树根的直接子节点。
     * {@code deep=true} 一次取整棵子树，此时必须同时传 {@code maxDepth} 或 {@code nodeTypes}，
     * 且硬上限 2000 个节点。<b>超管禁止以平台根为起点。</b>
     */
    @GetMapping("/tree")
    @SaCheckPermission("org:node:list")
    public R<List<NodeTreeVO>> tree(NodeTreeQuery query) {
        return R.ok(nodeQueryService.tree(query));
    }

    /**
     * §3.2 节点详情。{@code org_admin} / {@code teacher} / {@code super_admin}。
     *
     * <p>目标不在子树内返回 <b>404</b>（不暴露存在性）。
     */
    @GetMapping("/{id}")
    @SaCheckPermission("org:node:list")
    public R<NodeDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(nodeQueryService.detail(id));
    }

    /**
     * §3.3 修改节点。仅 {@code org_admin}。
     *
     * <p>只改展示属性；<b>{@code parentId} 必须走 §3.4 移动节点</b>。
     */
    @PutMapping("/{id}")
    @SaCheckPermission("org:node:edit")
    @OperLog(module = "组织树管理", action = "修改节点")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody NodeUpdateReq req) {
        nodeUpdateService.update(id, req);
        return R.ok();
    }

    /**
     * §3.4 移动节点。仅 {@code org_admin}。<b>全系统唯一改变树结构的入口。</b>
     *
     * <p><b>对客户端不可自动重试</b>（00-通用约定 §7.5）：超时后节点可能已移动成功，
     * 盲目重试会把它再移到另一位置；客户端应改为重新拉取节点详情确认当前 {@code parentId}。
     */
    @PutMapping("/{id}/move")
    @SaCheckPermission("org:node:move")
    @OperLog(module = "组织树管理", action = "移动节点")
    public R<NodeMovedVO> move(@PathVariable("id") Long id, @Valid @RequestBody NodeMoveReq req) {
        // 【F-114 定案三】revokeOutOfScopeGrants 必填、无默认值，不传在 @Valid 就已经 400。
        // explicitChoice 标记「这是操作人自己表的态」—— 选了 false 才写留痕，
        // 而模块 07 的内部封装走 none()，不写（它们从来没被问过这个问题）
        NodeMoveOptions options =
                NodeMoveOptions.explicitChoice(req.getReason(), req.getRevokeOutOfScopeGrants());
        return R.ok(nodeMoveService.move(id, req.getToParentId(), options));
    }

    /**
     * §3.5 节点停用 / 启用。仅 {@code org_admin}。
     *
     * <p>只写 {@code org_node.status} 一行；停用效果按节点类型自动区分，<b>无 {@code cascade} 参数</b>。
     */
    @PutMapping("/{id}/status")
    @SaCheckPermission("org:node:status")
    @OperLog(module = "组织树管理", action = "停用/启用节点")
    public R<NodeStatusChangedVO> changeStatus(@PathVariable("id") Long id,
                                               @Valid @RequestBody NodeStatusReq req) {
        return R.ok(nodeStatusService.changeStatus(id, req));
    }

    /**
     * §3.6 重置人员密码。{@code org_admin}；{@code teacher} 仅限其名下学员。
     *
     * <p>新密码明文<b>仅本次响应返回一次</b>，不落库、不可再查。
     * {@code @OperLog(saveParams = false)}：请求体里就是新密码明文，
     * <b>写进操作日志等于把它落了库</b>，与「明文永不落库」（PRD §7.3 安全条款 1）直接冲突。
     */
    @PutMapping("/{id}/password/reset")
    @SaCheckPermission("org:node:resetPwd")
    @OperLog(module = "组织树管理", action = "重置人员密码", saveParams = false)
    public R<NodePasswordResetVO> resetPassword(@PathVariable("id") Long id,
                                                @Valid @RequestBody(required = false)
                                                NodePasswordResetReq req) {
        NodePasswordResetReq body = req == null ? new NodePasswordResetReq() : req;
        return R.ok(nodePasswordResetService.reset(id, body));
    }
}
