package com.edumatrix.org.node.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.node.dto.NodeTreeQuery;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.NodeAccountMapper;
import com.edumatrix.org.node.mapper.NodeGrantScopeMapper;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.vo.NodeDetailVO;
import com.edumatrix.org.node.vo.NodeTreeVO;

/**
 * 组织树查询（03-02 §3.1）与节点详情（§3.2）。
 *
 * <h2>两种形态，默认是懒加载</h2>
 * <ul>
 *   <li><b>懒加载（默认）</b>：只返回展开点的<b>直接子节点</b>，走 {@code idx_tenant_parent_sort}；
 *   <li><b>{@code deep=true}</b>：一次取整棵子树，<b>必须同时传 {@code maxDepth} 或
 *       {@code nodeTypes}</b>，且硬上限 2000 个节点。
 * </ul>
 * <p>理由是 §3.1 说明段自己给的：机构根管理员的子树 = 全机构（单租户约 1.1 万节点），
 * 一次性返回的响应体约 5~8 MB，<b>而这是管理端登录后的第一个请求</b>。
 *
 * <p>（{@code parentId} 与 {@code deep} 只在说明段里、不在参数表里 —— 分册自身不一致，
 * 已登记为 F-26，见 {@code NodeTreeQuery} 的类注释。）
 */
@Service
public class NodeQueryService {

    /** §3.1 说明段的服务端硬上限：一次最多返回 2000 个节点，超出 {@code 400}。 */
    public static final int MAX_DEEP_NODES = 2000;

    private final OrgNodeMapper nodeMapper;
    private final NodeAccountMapper accountMapper;
    private final NodeGrantScopeMapper grantScopeMapper;
    private final SubtreeScopeHelper subtreeScopeHelper;
    private final CurrentNodeResolver currentNodeResolver;

    public NodeQueryService(OrgNodeMapper nodeMapper,
                            NodeAccountMapper accountMapper,
                            NodeGrantScopeMapper grantScopeMapper,
                            SubtreeScopeHelper subtreeScopeHelper,
                            CurrentNodeResolver currentNodeResolver) {
        this.nodeMapper = nodeMapper;
        this.accountMapper = accountMapper;
        this.grantScopeMapper = grantScopeMapper;
        this.subtreeScopeHelper = subtreeScopeHelper;
        this.currentNodeResolver = currentNodeResolver;
    }

    // =====================================================================
    // §3.1 组织树查询
    // =====================================================================

    public List<NodeTreeVO> tree(NodeTreeQuery query) {
        Long myNodeId = currentNodeResolver.requireCurrentNodeId();

        Long rootId = query.getRootId() == null ? myNodeId : query.getRootId();
        if (query.getRootId() != null) {
            // §3.1 数据权限：「传入 rootId 时该节点必须在子树内，否则返回 10107」——
            // rootId 是【调用方在参数里显式选的目标】，按契约 §2.4 三分法用 10107 而不是 404
            subtreeScopeHelper.assertTargetInSubtree(myNodeId, rootId);
        }

        // §3.1 说明段：【超管调用时禁止以平台根为起点】——必须显式指定某个租户的根节点，
        // 否则会一次性返回全平台所有租户的节点（100 机构约 110 万行）。
        // 超管的 node_id 就是 0，所以不传 rootId 时正好落在这一支上
        if (TenantHelper.isSuperAdminSession() && rootId == OrgNode.PLATFORM_ROOT_ID) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "超管必须显式指定某个租户的根节点作为 rootId，不得以平台根为起点");
        }

        OrgNode root = nodeMapper.selectById(rootId);
        if (root == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }

        List<Integer> nodeTypes = parseNodeTypes(query.getNodeTypes());
        boolean keywordSearch = hasText(query.getKeyword());

        List<NodeTreeVO> result = (query.isDeep() || keywordSearch)
                ? deepTree(root, query, nodeTypes)
                : lazyLevel(root, query, nodeTypes, myNodeId);
        return result;
    }

    /** 懒加载：只取展开点的直接子节点，{@code children} 一律为 {@code []}。 */
    private List<NodeTreeVO> lazyLevel(OrgNode root, NodeTreeQuery query,
                                       List<Integer> nodeTypes, Long myNodeId) {
        Long expandAt = query.getParentId() == null ? root.getId() : query.getParentId();
        if (query.getParentId() != null) {
            // parentId 同样是「我选的目标」，越界 10107
            subtreeScopeHelper.assertTargetInSubtree(myNodeId, expandAt);
        }
        List<OrgNode> children =
                nodeMapper.selectChildren(expandAt, nodeTypes, query.isIncludeDisabled());
        return toVOs(children);
    }

    /**
     * {@code deep=true} 或带 {@code keyword} 时：一次取整棵子树并拼成嵌套结构。
     *
     * <p><b>{@code keyword} 也走这条路</b>：命中的节点可能在子树的任意深度，
     * 只查一层是找不到的。它同样受 2000 上限约束。
     */
    private List<NodeTreeVO> deepTree(OrgNode root, NodeTreeQuery query, List<Integer> nodeTypes) {
        boolean keywordSearch = hasText(query.getKeyword());
        if (query.isDeep() && !keywordSearch
                && query.getMaxDepth() == null && nodeTypes.isEmpty()) {
            // §3.1 说明段：deep=true 时【必须同时传 maxDepth 或 nodeTypes】
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "deep=true 时必须同时传 maxDepth 或 nodeTypes 以收窄范围");
        }

        Integer maxAbsoluteDepth = (query.getMaxDepth() == null || query.getMaxDepth() <= 0)
                ? null : root.depth() + query.getMaxDepth();

        // 多取一行：拿到 MAX_DEEP_NODES + 1 行就说明超限，无需把整棵子树读进 JVM
        List<OrgNode> rows = nodeMapper.selectSubtree(root.selfPrefix(), nodeTypes,
                query.isIncludeDisabled(), maxAbsoluteDepth,
                keywordSearch ? query.getKeyword() : null, MAX_DEEP_NODES + 1);
        if (rows.size() > MAX_DEEP_NODES) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "本次查询涉及节点超过 " + MAX_DEEP_NODES + " 个，请收窄 maxDepth / nodeTypes / keyword");
        }

        if (keywordSearch) {
            rows = withAncestorChains(root, rows);
            if (rows.size() > MAX_DEEP_NODES) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "命中节点及其祖先链超过 " + MAX_DEEP_NODES + " 个，请收窄 keyword");
            }
        }
        return assemble(root.getId(), rows);
    }

    /**
     * 把命中节点的<b>全部祖先链</b>补回来（§3.1：「命中节点及其全部祖先链一并返回，
     * 未命中分支不返回」）。
     *
     * <p>只补<b>树根以下</b>的那一段：树根之上的祖先不属于本次返回的树，
     * 而平台根哨兵更是连租户都不属于（{@code NodePath#parseAncestorIds} 已跳过它）。
     */
    private List<OrgNode> withAncestorChains(OrgNode root, List<OrgNode> hits) {
        Set<Long> present = new LinkedHashSet<>();
        for (OrgNode hit : hits) {
            present.add(hit.getId());
        }
        Set<Long> missing = new LinkedHashSet<>();
        for (OrgNode hit : hits) {
            // 祖先链是【根在前】的，所以树根出现在最前面：要补的是它【之后】那一段。
            // 树根不在链里（它是平台根哨兵、已被 parseAncestorIds 跳过）时 indexOf 返回 -1，
            // 于是从 0 开始取整条链 —— 那也正确
            List<Long> chain = NodePath.parseAncestorIds(hit.getAncestors());
            for (int i = chain.indexOf(root.getId()) + 1; i < chain.size(); i++) {
                Long ancestorId = chain.get(i);
                if (!present.contains(ancestorId)) {
                    missing.add(ancestorId);
                }
            }
        }
        if (missing.isEmpty()) {
            return hits;
        }
        List<OrgNode> all = new ArrayList<>(hits);
        all.addAll(nodeMapper.selectByIds(new ArrayList<>(missing)));
        return all;
    }

    /**
     * 把扁平行拼成嵌套树，返回<b>树根的直接子节点</b>那一层。
     *
     * <p><b>父不在结果集里的节点整支丢弃</b> —— §3.1：「筛选时若某节点被排除，
     * 其子节点一并不返回（<b>保持树的连通性</b>）」。返回一个父节点不在响应里的孤儿节点，
     * 前端渲染出来会挂在错误的位置。
     */
    private List<NodeTreeVO> assemble(Long rootId, List<OrgNode> rows) {
        Map<Long, NodeTreeVO> byId = new LinkedHashMap<>();
        for (NodeTreeVO vo : toVOs(rows)) {
            byId.put(vo.getId(), vo);
        }
        List<NodeTreeVO> top = new ArrayList<>();
        for (NodeTreeVO vo : byId.values()) {
            if (rootId.equals(vo.getParentId())) {
                top.add(vo);
                continue;
            }
            NodeTreeVO parent = byId.get(vo.getParentId());
            if (parent != null) {
                parent.getChildren().add(vo);
            }
            // parent == null：这一支的上层被筛掉了，整支丢弃（保持连通性）
        }
        return top;
    }

    // =====================================================================
    // §3.2 节点详情
    // =====================================================================

    public NodeDetailVO detail(Long nodeId) {
        // 路径 {id} 上的操作对象越界 → 404，不暴露存在性（契约 §2.4 三分法）
        subtreeScopeHelper.assertInSubtree(currentNodeResolver.requireCurrentNodeId(), nodeId);

        OrgNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }

        NodeDetailVO vo = new NodeDetailVO();
        vo.setId(node.getId());
        vo.setParentId(node.getParentId());
        vo.setAncestors(node.getAncestors());
        vo.setNodeName(node.getNodeName());
        vo.setNodeType(node.getNodeType());
        vo.setRefUserId(node.getRefUserId());
        vo.setSort(node.getSort());
        vo.setStatus(node.getStatus());
        vo.setChildCount(node.getChildCount());
        vo.setStudentCount(node.getStudentCount());
        vo.setRemark(node.getRemark());
        vo.setCreateTime(node.getCreateTime());
        vo.setUpdateTime(node.getUpdateTime());

        NodeAccountMapper.UserBriefRow account = node.getRefUserId() == null
                ? null : accountMapper.selectUserBrief(node.getRefUserId());
        if (account != null) {
            vo.setRefUserName(account.getRealName());
            vo.setRefUserPhone(account.getPhone());
        }

        fillPath(node, vo);
        fillChildStat(nodeId, vo);
        fillGrantedResourceStat(nodeId, vo);
        return vo;
    }

    /**
     * 面包屑：自<b>租户根</b>到本节点，按层级正序。
     *
     * <p>祖先 id 由 {@link NodePath#parseAncestorIds} 拆出，<b>首位平台根哨兵已被跳过</b>。
     * 由此带来的现象是：按 {@code IN} 查名称时返回行数会比 {@code ancestors} 的段数少 1
     * ——<b>这是正确行为</b>（{@code NodePath} 类注释），不要"修"成放行哨兵。
     */
    private void fillPath(OrgNode node, NodeDetailVO vo) {
        List<Long> ancestorIds = NodePath.parseAncestorIds(node.getAncestors());
        List<NodeDetailVO.PathItem> path = new ArrayList<>();
        if (!ancestorIds.isEmpty()) {
            Map<Long, OrgNode> ancestors = new HashMap<>();
            for (OrgNode row : nodeMapper.selectByIds(ancestorIds)) {
                ancestors.put(row.getId(), row);
            }
            for (Long id : ancestorIds) {
                OrgNode row = ancestors.get(id);
                if (row != null) {
                    path.add(new NodeDetailVO.PathItem(row.getId(), row.getNodeName(), row.getNodeType()));
                }
            }
            OrgNode parent = ancestors.get(node.getParentId());
            vo.setParentName(parent == null ? null : parent.getNodeName());
        }
        path.add(new NodeDetailVO.PathItem(node.getId(), node.getNodeName(), node.getNodeType()));
        vo.setPath(path);
    }

    private void fillChildStat(Long nodeId, NodeDetailVO vo) {
        NodeDetailVO.ChildStat stat = new NodeDetailVO.ChildStat();
        for (OrgNodeMapper.NodeTypeCountRow row : nodeMapper.selectChildStat(nodeId)) {
            int count = row.getCnt() == null ? 0 : row.getCnt().intValue();
            Integer type = row.getNodeType();
            if (type == null) {
                continue;
            }
            switch (type) {
                case NodePath.NODE_TYPE_ADMIN -> stat.setAdminCount(count);
                case NodePath.NODE_TYPE_TEACHER -> stat.setTeacherCount(count);
                case NodePath.NODE_TYPE_STUDENT -> stat.setStudentCount(count);
                default -> {
                    // node_type = 0 只可能是平台根，不会出现在任何节点的子节点里
                }
            }
        }
        // orgCount 恒为 0：契约 §2.3 不设独立于人的组织单元节点（见 ChildStat 的注释）
        vo.setChildStat(stat);
    }

    private void fillGrantedResourceStat(Long nodeId, NodeDetailVO vo) {
        NodeDetailVO.GrantedResourceStat stat = new NodeDetailVO.GrantedResourceStat();
        for (NodeGrantScopeMapper.ResourceTypeCountRow row
                : grantScopeMapper.selectGrantedResourceStat(nodeId)) {
            int count = row.getCnt() == null ? 0 : row.getCnt().intValue();
            Integer type = row.getResourceType();
            if (type == null) {
                continue;
            }
            switch (type) {
                case 1 -> stat.setCourseCount(count);
                case 2 -> stat.setQuestionCount(count);
                case 3 -> stat.setVideoCount(count);
                default -> {
                    // 契约 §5 resource_type 只有 1/2/3
                }
            }
        }
        vo.setGrantedResourceStat(stat);
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /**
     * 解析 {@code nodeTypes=1,2} 逗号串。
     *
     * <p><b>{@code 0} 平台超管不接受</b>：§3.1 参数表逐字「取值 {@code 1} 管理员
     * {@code 2} 教师 {@code 3} 学生（{@code 0} 平台超管<b>仅超管自身可见</b>）」。
     * 而平台根是全树唯一一行、不属于任何租户，作为筛选值没有意义。
     */
    private static List<Integer> parseNodeTypes(String raw) {
        List<Integer> types = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return types;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int value;
            try {
                value = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "nodeTypes 只能是 1/2/3 的逗号串");
            }
            if (value < NodePath.NODE_TYPE_ADMIN || value > NodePath.NODE_TYPE_STUDENT) {
                throw new BizException(ErrorCode.BAD_REQUEST, "nodeTypes 只能是 1/2/3 的逗号串");
            }
            if (!types.contains(value)) {
                types.add(value);
            }
        }
        return types;
    }

    /**
     * 批量补 {@code refUserName}（§3.1 响应字段说明：<b>恒非空</b>）。
     *
     * <p>一次查询取回本批全部账号的姓名，而不是逐行查 —— 一层树最多 2000 行。
     */
    private List<NodeTreeVO> toVOs(List<OrgNode> rows) {
        List<NodeTreeVO> vos = new ArrayList<>(rows.size());
        List<Long> userIds = new ArrayList<>(rows.size());
        for (OrgNode row : rows) {
            if (row.getRefUserId() != null) {
                userIds.add(row.getRefUserId());
            }
        }
        Map<Long, String> nameByUserId = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (NodeAccountMapper.UserBriefRow account : accountMapper.selectUserBriefs(userIds)) {
                nameByUserId.put(account.getId(), account.getRealName());
            }
        }
        for (OrgNode row : rows) {
            NodeTreeVO vo = new NodeTreeVO();
            vo.setId(row.getId());
            vo.setParentId(row.getParentId());
            vo.setAncestors(row.getAncestors());
            vo.setNodeName(row.getNodeName());
            vo.setNodeType(row.getNodeType());
            vo.setRefUserId(row.getRefUserId());
            vo.setRefUserName(nameByUserId.get(row.getRefUserId()));
            vo.setSort(row.getSort());
            vo.setStatus(row.getStatus());
            vo.setChildCount(row.getChildCount());
            vo.setStudentCount(row.getStudentCount());
            vos.add(vo);
        }
        return vos;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
