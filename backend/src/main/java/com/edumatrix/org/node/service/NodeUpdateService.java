package com.edumatrix.org.node.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.node.dto.NodeUpdateReq;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.NodeAccountMapper;
import com.edumatrix.org.node.mapper.OrgNodeMapper;

/**
 * 修改节点（03-02 §3.3）。<b>只改展示属性</b>：{@code nodeName} / {@code sort} / {@code remark}。
 *
 * <p><b>改父一律走 §3.4 移动节点</b>（{@code NodeMoveService}）——
 * 在这里顺手写一句 {@code parent_id} 就是绕过 {@code ancestors} 重算与异动轨迹，
 * 而 {@code ancestors} 正是鉴权依据，裂开即越权（契约 §9.2 铁律 1）。
 * {@code NodeUpdateReq} 里因此<b>没有</b> {@code parentId} / {@code nodeType} /
 * {@code refUserId} 三个字段 —— 不给比给了再拒绝更难写错。
 */
@Service
public class NodeUpdateService {

    private final OrgNodeMapper nodeMapper;
    private final NodeAccountMapper accountMapper;
    private final SubtreeScopeHelper subtreeScopeHelper;
    private final CurrentNodeResolver currentNodeResolver;

    public NodeUpdateService(OrgNodeMapper nodeMapper,
                             NodeAccountMapper accountMapper,
                             SubtreeScopeHelper subtreeScopeHelper,
                             CurrentNodeResolver currentNodeResolver) {
        this.nodeMapper = nodeMapper;
        this.accountMapper = accountMapper;
        this.subtreeScopeHelper = subtreeScopeHelper;
        this.currentNodeResolver = currentNodeResolver;
    }

    /**
     * @param nodeId 目标节点。<b>越界返回 {@code 10107}</b> ——
     *               §3.3 的数据权限栏与「相关业务错误码」都写的是 {@code 10107}，
     *               不是路径 {@code {id}} 常用的 404。<b>按本接口自己的分册条目实现</b>：
     *               契约 §2.4 的三分法是全系统口径，而具体接口的错误码表是它的应用；
     *               此处两者不同的地方，分册对这个接口更具体（§3.2 / §3.6 就都写的是 404）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long nodeId, NodeUpdateReq req) {
        subtreeScopeHelper.assertTargetInSubtree(currentNodeResolver.requireCurrentNodeId(), nodeId);

        OrgNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }

        // 同父节点下 node_name 唯一（契约 §2.3 约束 5），排除自己
        if (nodeMapper.countSameNameSibling(node.getParentId(), req.getNodeName(), nodeId) > 0) {
            throw new BizException(ErrorCode.NODE_NAME_DUPLICATED);
        }

        OrgNode update = new OrgNode();
        update.setId(nodeId);
        update.setNodeName(req.getNodeName());
        update.setSort(req.getSort());
        update.setRemark(req.getRemark());
        nodeMapper.updateById(update);

        // §3.3：「人员节点的 nodeName 修改会【同步】sys_user.real_name」。
        // org_node.node_name 的 DDL 列注释同样写着「与 sys_user.real_name 同步」——
        // 两处必须同值，否则组织树上叫一个名字、账号列表里叫另一个
        if (node.getRefUserId() != null) {
            accountMapper.updateRealName(node.getRefUserId(), req.getNodeName(), TenantHelper.getUserId());
        }
    }
}
