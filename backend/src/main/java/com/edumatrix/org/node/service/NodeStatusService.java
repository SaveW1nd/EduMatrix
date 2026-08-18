package com.edumatrix.org.node.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.frozen.FrozenNodeCache;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.org.node.dto.NodeStatusReq;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.vo.NodeStatusChangedVO;

/**
 * 节点停用 / 启用（03-02 §3.5）。<b>可逆的软冻结，不删除任何数据。</b>
 *
 * <h2>只写 {@code org_node.status} 一行，绝不同步写 {@code sys_user.status}</h2>
 * <p>契约 §2.3：{@code org_node.status} 是停用的<b>唯一权威</b>；
 * {@code sys_user.status} 只表达与组织无关的<b>账号级封禁</b>，仅超管可写（03-01 §2.6）。
 * 曾经的「停用时同步置 {@code sys_user.status=1}」会造成<b>停用可逆、启用不可逆</b> ——
 * 启用只改回 {@code org_node.status}，账号侧那个 1 无人复位，
 * 而机构管理员<b>没有任何接口能改它</b>，用户永久登不进去、只能提工单改库。
 *
 * <h2>两条顺序约束，方向都朝「宁可多拦一瞬」</h2>
 * <pre>
 * 停用：先 SADD 冻结集  →  再提交事务
 * 启用：先提交事务      →  再 SREM
 * </pre>
 * <p>反过来会出现「库里已停用但 Redis 还没写」的<b>放行窗口</b>。
 * {@link FrozenNodeCache#add} 遇 Redis 故障<b>直接抛</b>，于是停用事务回滚，
 * 库与冻结集一致地「都没停」。
 *
 * <p>这与模块 04 的「先落库再踢线」<b>不冲突</b>：两处遵循的是同一条原则的两种落法 ——
 * <b>让拦截依据先生效</b>。那里租户状态本身就是拦截依据，这里冻结集才是。
 *
 * <p><b>这个顺序还有另一个后果，一并写在这里</b>：{@code SADD} 成功之后、事务提交之前，
 * 若事务<b>因任何原因回滚</b>（本方法后续步骤失败、外层事务回滚、进程崩溃），
 * 就会留下<b>「库里 {@code status = 0} 未停用，冻结集里却有它」</b>的状态 ——
 * 该节点（若是管理员，还含其整棵子树）<b>登不进来，而组织树上看不出任何异常</b>。
 *
 * <p><b>这是被接受的代价，方向与两条顺序约束一致：宁可多拦一瞬，不可漏放一瞬。</b>
 * 它也<b>可自愈</b>：再调一次本接口传 {@code status = 0}（启用）即会 {@code SREM}，
 * 而那条路径不要求节点当前必须是停用态。<b>与契约 §2.3 拆掉 {@code sys_user.status}
 * 侧路时要避免的「启用不可逆」不是一回事</b> —— 那个没有任何接口能修复，这个有。
 *
 * <h2>不做级联写库</h2>
 * <p>停用只改 <b>1 行</b>。分支冻结靠登录校验查祖先链 + 每请求鉴权比对冻结集实现
 * （契约 §2.3）。级联写库要为一个 1.1 万人的分支写 2.2 万行，中途失败留下半停用状态，
 * 且恢复时无法区分「被级联停的」与「本来就单独停的」。
 */
@Service
public class NodeStatusService {

    private final OrgNodeMapper nodeMapper;
    private final FrozenNodeCache frozenNodeCache;
    private final SubtreeScopeHelper subtreeScopeHelper;
    private final CurrentNodeResolver currentNodeResolver;

    public NodeStatusService(OrgNodeMapper nodeMapper,
                             FrozenNodeCache frozenNodeCache,
                             SubtreeScopeHelper subtreeScopeHelper,
                             CurrentNodeResolver currentNodeResolver) {
        this.nodeMapper = nodeMapper;
        this.frozenNodeCache = frozenNodeCache;
        this.subtreeScopeHelper = subtreeScopeHelper;
        this.currentNodeResolver = currentNodeResolver;
    }

    @Transactional(rollbackFor = Exception.class)
    public NodeStatusChangedVO changeStatus(Long nodeId, NodeStatusReq req) {
        // §3.5 数据权限：目标必须在子树内，否则 10107
        Long myNodeId = currentNodeResolver.requireCurrentNodeId();
        subtreeScopeHelper.assertTargetInSubtree(myNodeId, nodeId);
        // §3.5「相关业务错误码」：10012 不允许对当前登录账号执行该操作（停用自己所在节点）。
        // 数据权限栏把「不得是自己」并进了 10107 那一句，而错误码表给了更具体的 10012，
        // 且 03-01 §2.3/§2.4/§2.6 三处同形操作用的都是 10012 —— 取更具体的那个
        if (nodeId.equals(myNodeId)) {
            throw new BizException(ErrorCode.OPERATION_ON_SELF_FORBIDDEN);
        }

        OrgNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }

        boolean disabling = req.getStatus() == OrgNode.STATUS_DISABLED;
        if (disabling) {
            // 【先 SADD，再提交事务】——Redis 故障时本行直接抛，事务回滚，两边一致地「都没停」
            frozenNodeCache.add(node.getTenantId(), nodeId);
        }

        OrgNode update = new OrgNode();
        update.setId(nodeId);
        update.setStatus(req.getStatus());
        update.setRemark(req.getRemark());
        nodeMapper.updateById(update);

        if (!disabling) {
            // 【先提交事务，再 SREM】。吞掉异常的后果是「库里已启用、冻结集里还留着」，
            // 该账号永远登不进来且没有任何接口能修复它 —— 与「停用可逆、启用不可逆」同类错误。
            // 所以 FrozenNodeCache#remove 不吞异常，这里也不吞
            afterCommit(() -> frozenNodeCache.remove(node.getTenantId(), nodeId));
        }

        int affected = affectedNodeCount(node);
        NodeStatusChangedVO vo = new NodeStatusChangedVO();
        vo.setNodeId(nodeId);
        vo.setStatus(req.getStatus());
        vo.setAffectedNodeCount(affected);
        // 每个节点都是一个人（契约 §2.3，ref_user_id 全部非空 + uk_ref_user_id），
        // 所以受影响账号数恒等于受影响节点数
        vo.setAffectedUserCount(affected);
        // update_time 是 ON UPDATE CURRENT_TIMESTAMP，读回来而不是在 Java 侧取 now()
        OrgNode persisted = nodeMapper.selectById(nodeId);
        vo.setUpdateTime(persisted == null ? null : persisted.getUpdateTime());
        return vo;
    }

    /**
     * 受影响的节点数 —— <b>影响面，不是写入行数</b>（库里只写了 1 行）。
     *
     * <p>按 §3.5「停用效果按节点类型自动区分，无 {@code cascade} 参数」：
     * <ul>
     *   <li><b>管理员节点</b>：本人 + 整棵子树（分支冻结）；
     *   <li><b>教师节点</b>：<b>仅本人</b>。名下学员照常登录学习 ——
     *       级联会让整批学员突然登不进去，契约 §2.3 称之为<b>业务事故</b>。
     *       把学员算进 {@code affectedNodeCount} 就是把一件没发生的事写进响应；
     *   <li><b>学生节点</b>：仅本人（无子树）。
     * </ul>
     */
    private int affectedNodeCount(OrgNode node) {
        Integer type = node.getNodeType();
        boolean branchFreeze = type != null
                && (type == NodePath.NODE_TYPE_ADMIN || type == NodePath.NODE_TYPE_PLATFORM);
        if (!branchFreeze) {
            return 1;
        }
        return 1 + (int) nodeMapper.countSubtreeNodes(node.selfPrefix());
    }

    /** 事务提交后执行；无事务时直接执行（见 {@code NodeMoveService#registerAfterCommit} 的理由）。 */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
