package com.edumatrix.org.member.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.org.member.dto.AdminCreateReq;
import com.edumatrix.org.member.dto.AdminPageQuery;
import com.edumatrix.org.member.dto.AdminUpdateReq;
import com.edumatrix.org.member.mapper.MemberQueryMapper;
import com.edumatrix.org.member.vo.AdminVO;
import com.edumatrix.org.member.vo.MemberCreatedVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.service.CurrentNodeResolver;

/**
 * 管理员管理（03-02 §4.1~§4.4，接口 7 / 8 / 9 / 10）。
 *
 * <p><b>管理员没有档案表</b>——`org_admin` 这张表不存在，也不该存在：契约 §2.1
 * 「机构管理员与下级管理员<b>角色完全相同</b>，差别仅在节点在树中的位置」，
 * 一个管理员 = 一个 {@code sys_user}（{@code user_type=1}）+ 一个 {@code org_node}
 * （{@code node_type=1}）。所以本类的建人是「两写一事务」，不是三写。
 */
@Service
public class OrgAdminService {

    private static final String ROLE_KEY = "org_admin";

    private final MemberWriteSupport writeSupport;
    private final MemberQueryMapper queryMapper;
    private final OrgNodeMapper nodeMapper;
    private final CurrentNodeResolver currentNodeResolver;

    public OrgAdminService(MemberWriteSupport writeSupport,
                           MemberQueryMapper queryMapper,
                           OrgNodeMapper nodeMapper,
                           CurrentNodeResolver currentNodeResolver) {
        this.writeSupport = writeSupport;
        this.queryMapper = queryMapper;
        this.nodeMapper = nodeMapper;
        this.currentNodeResolver = currentNodeResolver;
    }

    // =====================================================================
    // 接口 7 §4.1 管理员分页列表
    // =====================================================================

    public PageResult<AdminVO> page(AdminPageQuery query) {
        Long myNodeId = currentNodeResolver.requireCurrentNodeId();
        OrgNode root = resolveScopeRoot(query.getNodeId(), myNodeId);

        IPage<MemberQueryMapper.AdminRow> result = queryMapper.pageAdmins(
                new Page<>(PageResult.normalizePageNum(query.getPageNum()),
                        PageResult.normalizePageSize(query.getPageSize())),
                root.getId(), root.selfPrefix(), myNodeId,
                Boolean.TRUE.equals(query.getDirectOnly()),
                blankToNull(query.getRealName()), blankToNull(query.getPhone()), query.getStatus());

        List<MemberQueryMapper.AdminRow> rows = result.getRecords();
        // 三个子树计数一次查全页，不逐行查 —— 一页最多 100 行，逐行就是 300 次往返
        Map<Long, MemberQueryMapper.SubtreeStatRow> stats = loadStats(rows);
        Map<Long, String> parentNames = loadParentNames(rows);

        List<AdminVO> list = new ArrayList<>(rows.size());
        for (MemberQueryMapper.AdminRow row : rows) {
            AdminVO vo = new AdminVO();
            vo.setNodeId(row.getNodeId());
            vo.setUserId(row.getUserId());
            vo.setUsername(row.getUsername());
            vo.setRealName(row.getRealName());
            vo.setPhone(row.getPhone());
            vo.setNodeName(row.getNodeName());
            vo.setParentNodeId(row.getParentNodeId());
            vo.setParentNodeName(parentNames.get(row.getParentNodeId()));
            vo.setNodePath(writeSupport.nodePath(row.getNodeId()));
            vo.setSort(row.getSort());
            vo.setStatus(row.getStatus());
            MemberQueryMapper.SubtreeStatRow stat = stats.get(row.getNodeId());
            vo.setSubAdminCount(stat == null ? 0 : stat.getSubAdminCount());
            vo.setTeacherCount(stat == null ? 0 : stat.getTeacherCount());
            vo.setStudentCount(stat == null ? 0 : stat.getStudentCount());
            vo.setLastLoginTime(row.getLastLoginTime());
            vo.setRemark(row.getRemark());
            vo.setCreateTime(row.getCreateTime());
            vo.setUpdateTime(row.getUpdateTime());
            list.add(vo);
        }
        return PageResult.of(result.getTotal(), list);
    }

    // =====================================================================
    // 接口 8 §4.2 新建下级管理员
    // =====================================================================

    /**
     * <b>两写一事务</b>：{@code sys_user} + {@code org_node} 任一失败整体回滚
     * （PRD F1-3 规则 1）。管理员没有档案表，见类注释。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberCreatedVO create(AdminCreateReq req) {
        MemberWriteSupport.PersonCreated created = writeSupport.createPerson(
                new MemberWriteSupport.PersonCreateCmd(
                        req.getParentNodeId(), NodePath.NODE_TYPE_ADMIN, ROLE_KEY,
                        req.getRealName(), req.getPhone(), req.getUsername(),
                        req.getNodeName(), req.getSort(), req.getInitPassword(), req.getRemark()));
        writeSupport.warnTemplateNotApplied(req.getTemplateId(), created.nodeId());
        return toCreatedVO(created, req.getParentNodeId());
    }

    // =====================================================================
    // 接口 9 §4.3 修改管理员
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public void update(Long nodeId, AdminUpdateReq req) {
        // §4.3 数据权限：子树内且【不得是自己】→ 10107 / 10012
        OrgNode node = writeSupport.requireNodeInMyScope(nodeId, NodePath.NODE_TYPE_ADMIN);
        writeSupport.assertNotSelf(nodeId);

        writeSupport.updateAccount(node.getRefUserId(), req.getRealName(),
                req.getPhone(), blankToNull(req.getUsername()));
        // nodeName 留空表示不修改；留空时【不能】退化成 realName —— 那会把一个
        // 有意起过名的节点（如「华东大区」）在一次改手机号的操作里悄悄改成人名
        writeSupport.updateNodeProfile(node, blankToNull(req.getNodeName()),
                req.getSort(), req.getRemark());
    }

    // =====================================================================
    // 接口 10 §4.4 删除管理员
    // =====================================================================

    /**
     * 逻辑删除。<b>节点下存在任何未删除子节点时禁止删除 → {@code 10108}</b>
     * （§4.4：须先通过接口 4 / 21 / 22 把下级迁移到其他节点）。
     *
     * <p><b>与教师的 {@code 10206} 是两个码</b>：管理员下面可能是管理员/教师/学生三种，
     * 提示语是「请先迁移子节点」；教师下面只可能是学员，提示语是「请先转走名下学员」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long nodeId) {
        OrgNode node = writeSupport.requireNodeInMyScope(nodeId, NodePath.NODE_TYPE_ADMIN);
        writeSupport.assertNotSelf(nodeId);
        // 子节点保护【先于任何写入】
        if (liveChildCount(nodeId) > 0) {
            throw new BizException(ErrorCode.NODE_HAS_CHILDREN);
        }
        writeSupport.deletePerson(node);
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /**
     * 查询范围起点：不传取当前登录人所在节点；传了必须在子树内，否则 {@code 10107}。
     */
    private OrgNode resolveScopeRoot(Long nodeId, Long myNodeId) {
        if (nodeId == null) {
            OrgNode mine = nodeMapper.selectById(myNodeId);
            if (mine == null) {
                throw new BizException(ErrorCode.NODE_NOT_FOUND);
            }
            return mine;
        }
        return writeSupport.requireParentInMyScope(nodeId);
    }

    private Map<Long, MemberQueryMapper.SubtreeStatRow> loadStats(
            List<MemberQueryMapper.AdminRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = rows.stream().map(MemberQueryMapper.AdminRow::getNodeId).toList();
        Map<Long, MemberQueryMapper.SubtreeStatRow> byId = new LinkedHashMap<>();
        for (MemberQueryMapper.SubtreeStatRow stat : queryMapper.selectSubtreeStats(ids)) {
            byId.put(stat.getRootId(), stat);
        }
        return byId;
    }

    private Map<Long, String> loadParentNames(List<MemberQueryMapper.AdminRow> rows) {
        List<Long> parentIds = rows.stream()
                .map(MemberQueryMapper.AdminRow::getParentNodeId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (OrgNode n : nodeMapper.selectByIds(parentIds)) {
            names.put(n.getId(), n.getNodeName());
        }
        return names;
    }

    private MemberCreatedVO toCreatedVO(MemberWriteSupport.PersonCreated created, Long parentNodeId) {
        MemberCreatedVO vo = new MemberCreatedVO();
        vo.setNodeId(created.nodeId());
        vo.setUserId(created.userId());
        vo.setUsername(created.username());
        vo.setInitPassword(created.plainPassword());
        vo.setPwdResetFlag(1);
        vo.setParentNodeId(parentNodeId);
        vo.setAncestors(created.ancestors());
        vo.setNodePath(writeSupport.nodePath(created.nodeId()));
        vo.setChangeType(1);
        return vo;
    }

    /**
     * 未删除的直接子节点数。
     *
     * <p>走模块 06 的 {@code selectChildStat}（它按 {@code node_type} 分组计数），
     * <b>不新增 Mapper 方法</b> —— §4.4 要的只是「有没有子节点」，
     * 而那条 SQL 已经能回答，多加一条就是同一件事的第二个真相源。
     */
    private long liveChildCount(Long nodeId) {
        long total = 0;
        for (OrgNodeMapper.NodeTypeCountRow row : nodeMapper.selectChildStat(nodeId)) {
            total += row.getCnt() == null ? 0 : row.getCnt();
        }
        return total;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
