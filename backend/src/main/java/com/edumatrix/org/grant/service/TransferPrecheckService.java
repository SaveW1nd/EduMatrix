package com.edumatrix.org.grant.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.resource.GrantableResourceReader;
import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.org.grant.dto.TransferPrecheckReq;
import com.edumatrix.org.grant.mapper.TransferPrecheckMapper;
import com.edumatrix.org.grant.vo.TransferPrecheckVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;

/**
 * 接口 52 归属变更影响面预检（03-02 §6.12、F-21 定案）。
 *
 * <h2>它为什么归模块 11（F-80）</h2>
 * <p>{@code 00-通用约定.md}:465 逐字：「<b>签名在模块 07 敲定，实现落在模块 11</b>」。
 * 三处依赖全部在本模块及其前置：跨管辖判定要 {@code owner_node_id} 与有效授权链、
 * {@code resourceName} 在三张资源表、{@code grantableByMe} 要完整的拥有性判定。
 *
 * <p><b>04 §A 的核对行把它算进模块 07 的 21 个，而 §B 模块 07 的表只列了 20 个</b>——
 * 那一行自称「验证没有接口无人认领」，却只校验求和、不校验每个模块的数
 * 与 §B 表行数是否一致。于是 52 号成了唯一的空档。已随文档提交订正。
 *
 * <h2>只读预检：<b>无权的资源不返回 {@code 10301}</b></h2>
 * <p>§6.12 说明段逐字：{@code 10301} 是<b>执行接口 38 时</b>的拒绝码；
 * 在只读预检里抛它会让<b>整个预检失败</b>，而操作者<b>恰恰需要看到</b>
 * 「这门课我授不了，得找共同上级」—— 接口 22 的完整流程本就是三步
 *（转交 → 授权给新管理员 → 分配导师并授权给该导师），<b>第二步必须由共同上级执行</b>。
 * 故用 {@code grantableByMe = false} 标记，不抛码。
 *
 * <h2>按<b>资源</b>归并，不按人</h2>
 * <p>批量上限 500 人，逐人弹窗不可用；前端若自行调接口 41 做差集，500 人要调 501 次。
 */
@Service
public class TransferPrecheckService {

    /** 学员样本上限（§6.12 字段说明「最多返回前 50 个」）。 */
    private static final int SAMPLE_LIMIT = 50;

    private static final int ACTION_ASSIGN_TEACHER = 2;
    private static final int ACTION_TRANSFER_ADMIN = 3;

    private final TransferPrecheckMapper precheckMapper;
    private final OrgNodeMapper nodeMapper;
    private final ResourceOwnerChecker ownerChecker;
    private final GrantableResourceReader grantableReader;
    private final SubtreeScopeHelper subtreeScope;
    private final CurrentNodeProvider currentNodeProvider;

    public TransferPrecheckService(TransferPrecheckMapper precheckMapper,
                                   OrgNodeMapper nodeMapper,
                                   ResourceOwnerChecker ownerChecker,
                                   GrantableResourceReader grantableReader,
                                   SubtreeScopeHelper subtreeScope,
                                   CurrentNodeProvider currentNodeProvider) {
        this.precheckMapper = precheckMapper;
        this.nodeMapper = nodeMapper;
        this.ownerChecker = ownerChecker;
        this.grantableReader = grantableReader;
        this.subtreeScope = subtreeScope;
        this.currentNodeProvider = currentNodeProvider;
    }

    /** <b>只读，不改任何数据。</b> */
    public TransferPrecheckVO precheck(TransferPrecheckReq req) {
        Long myNodeId = currentNodeProvider.requireCurrentNodeId();

        OrgNode target = nodeMapper.selectById(req.getToNodeId());
        if (target == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND, null, req.getToNodeId(), null);
        }
        // §6.12 数据权限栏：与接口 20/21/22 【同一判定】，越界 10107 ——
        // 预检与执行不得有两套口径，否则「预检说能做、执行说不能」
        subtreeScope.assertTargetInSubtree(myNodeId, req.getToNodeId());

        int action = inferAction(target.getNodeType());

        List<TransferPrecheckMapper.StudentNodeRow> students =
                precheckMapper.selectStudentNodes(req.getStudentIds());
        for (TransferPrecheckMapper.StudentNodeRow row : students) {
            subtreeScope.assertTargetInSubtree(myNodeId, row.getNodeId());
        }
        if (students.isEmpty()) {
            return emptyResult(action, req.getStudentIds().size());
        }

        Map<Long, TransferPrecheckMapper.StudentNodeRow> byNode = new LinkedHashMap<>();
        students.forEach(row -> byNode.put(row.getNodeId(), row));

        // 移动【之后】学生节点的祖先链 = 目标节点的自身路径前缀。
        // 用它判「链还通不通」—— 与 OutOfScopeGrantResolver 同一条思路：
        // 判定复用 canRegrant，只是把「链从哪来」变成参数（C8 那个缓存快照缺陷的处置）
        String futureAncestors = target.getAncestors() == null || target.getAncestors().isEmpty()
                ? String.valueOf(target.getId())
                : target.getAncestors() + "," + target.getId();

        List<TransferPrecheckMapper.HeldGrantRow> held =
                precheckMapper.selectHeldGrants(List.copyOf(byNode.keySet()));

        // (类型, 资源) → 会失去链路的学员节点
        Map<ResourceType, Map<Long, Set<Long>>> affected = new EnumMap<>(ResourceType.class);
        for (TransferPrecheckMapper.HeldGrantRow row : held) {
            ResourceType type = ResourceType.of(row.getResourceType()).orElse(null);
            if (type == null) {
                continue;
            }
            // 移动之后，这个学生节点还能不能经新祖先链拿到该资源？
            // 用 canRegrant 的链判定：owner 或链上逐层持有。学生是叶子、
            // 判的是「链通不通」而不是「他能不能再下发」—— 同一条链判据，两种用途
            boolean intactAfterMove = !ownerChecker
                    .regrantableIds(type, List.of(row.getResourceId()), row.getTargetNodeId(),
                            futureAncestors)
                    .isEmpty();
            if (intactAfterMove) {
                continue;
            }
            affected.computeIfAbsent(type, k -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getResourceId(), k -> new LinkedHashSet<>())
                    .add(row.getTargetNodeId());
        }

        return build(action, req.getStudentIds().size(), affected, byNode, myNodeId);
    }

    private TransferPrecheckVO build(int action, int requestedStudents,
                                     Map<ResourceType, Map<Long, Set<Long>>> affected,
                                     Map<Long, TransferPrecheckMapper.StudentNodeRow> byNode,
                                     Long myNodeId) {
        List<TransferPrecheckVO.OutOfScopeResource> rows = new ArrayList<>();
        Set<Long> affectedStudents = new LinkedHashSet<>();
        int grantable = 0;

        for (Map.Entry<ResourceType, Map<Long, Set<Long>>> byType : affected.entrySet()) {
            ResourceType type = byType.getKey();
            Set<Long> resourceIds = byType.getValue().keySet();
            Map<Long, String> names = grantableReader.namesOf(type, resourceIds);
            Set<Long> mine = ownerChecker.regrantableIds(type, resourceIds, myNodeId);

            for (Map.Entry<Long, Set<Long>> entry : byType.getValue().entrySet()) {
                Long resourceId = entry.getKey();
                Set<Long> nodes = entry.getValue();
                affectedStudents.addAll(nodes);

                TransferPrecheckVO.OutOfScopeResource vo = new TransferPrecheckVO.OutOfScopeResource();
                vo.setResourceType(type.code());
                vo.setResourceId(resourceId);
                vo.setResourceName(names.get(resourceId));
                vo.setAffectedStudentCount(nodes.size());
                boolean canGrant = mine.contains(resourceId);
                vo.setGrantableByMe(canGrant);
                if (canGrant) {
                    grantable++;
                }
                List<TransferPrecheckVO.SampleStudent> sample = nodes.stream().limit(SAMPLE_LIMIT)
                        .map(nodeId -> new TransferPrecheckVO.SampleStudent(nodeId,
                                byNode.get(nodeId) == null ? null : byNode.get(nodeId).getRealName()))
                        .toList();
                vo.setSampleStudents(sample);
                vo.setSampleTruncated(nodes.size() > SAMPLE_LIMIT);
                rows.add(vo);
            }
        }

        TransferPrecheckVO vo = new TransferPrecheckVO();
        vo.setAction(action);
        vo.setActionName(actionName(action));
        vo.setStudentCount(requestedStudents);
        vo.setSummary(new TransferPrecheckVO.Summary(rows.size(), grantable,
                rows.size() - grantable, affectedStudents.size()));
        vo.setOutOfScopeGrants(rows);
        vo.setOptions(options());
        vo.setLearningRecordsRetained(true);
        return vo;
    }

    private TransferPrecheckVO emptyResult(int action, int requestedStudents) {
        TransferPrecheckVO vo = new TransferPrecheckVO();
        vo.setAction(action);
        vo.setActionName(actionName(action));
        vo.setStudentCount(requestedStudents);
        vo.setSummary(new TransferPrecheckVO.Summary(0, 0, 0, 0));
        vo.setOptions(options());
        vo.setLearningRecordsRetained(true);
        return vo;
    }

    /**
     * 动作类型由 {@code toNodeId} 的 {@code node_type} 推断，与接口 4 的
     * {@code changeType} 推断<b>同源</b>，不另立一套分支（§6.12 说明段）。
     */
    private static int inferAction(Integer nodeType) {
        if (nodeType != null && nodeType == NodePath.NODE_TYPE_TEACHER) {
            return ACTION_ASSIGN_TEACHER;
        }
        if (nodeType != null && nodeType == NodePath.NODE_TYPE_ADMIN) {
            return ACTION_TRANSFER_ADMIN;
        }
        // 学生节点下不能挂学生（契约 §2.3 结构约束 1）
        throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
    }

    private static String actionName(int action) {
        return action == ACTION_ASSIGN_TEACHER ? "分配导师" : "转交管理员";
    }

    /** {@code keep} 恒排第一且 {@code isDefault = true}（F-21 定案第 1 条）。 */
    private static List<TransferPrecheckVO.Option> options() {
        return List.of(
                new TransferPrecheckVO.Option("keep", "保持现状（推荐）", true,
                        "学员继续正常使用；新上级看不到该资源，也无法再下发。"
                                + "跨管辖授权是契约 §2.5 规则 6 认可的合法状态"),
                new TransferPrecheckVO.Option("revoke", "一并回收跨管辖授权", false,
                        "已产生的学习记录（vod_watch_progress 学习进度、hw_answer_sheet 答卷、"
                                + "hw_wrong_book 错题本）一律保留不删，仅失去继续访问权"));
    }
}
