package com.edumatrix.org.grant.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.id.IdWorker;
import com.edumatrix.common.resource.GrantableResourceReader;
import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.common.subtree.NodeNameReader;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.grant.dto.GrantCreateReq;
import com.edumatrix.org.grant.entity.OrgResourceGrant;
import com.edumatrix.org.grant.mapper.OrgResourceGrantMapper;
import com.edumatrix.org.grant.vo.DuplicatedGrantVO;
import com.edumatrix.org.grant.vo.GrantCreatedVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;

/**
 * 模块 11 的<b>写侧</b>：接口 38 授权资源给节点（03-02 §9.2）。
 *
 * <h2>四个错误码互不代偿</h2>
 * <table border="1">
 *   <caption>10301 / 10302 / 10303 / 10308 各自回答一个问题</caption>
 *   <tr><th>码</th><th>回答的问题</th></tr>
 *   <tr><td>{@code 10301}</td><td>这个<b>资源</b>我能不能授</td></tr>
 *   <tr><td>{@code 10302}</td><td>这个<b>目标</b>我能不能授给</td></tr>
 *   <tr><td>{@code 10303}</td><td>这一对<b>已经</b>授过了</td></tr>
 *   <tr><td>{@code 10308}</td><td>这个<b>类型</b>根本不该授给这种节点</td></tr>
 * </table>
 * <p>任何一个用另一个的码返回，都会让前端把「重新选资源」和「重新选人」的提示<b>反过来</b>。
 */
@Service
public class GrantWriteService {

    /**
     * 单次写入的授权行数硬上限（契约 §2.5 模板段、03-02 §9.2 参数表）。
     *
     * <p>模板明细上限 2000 项 × 目标节点上限 500 个 = 100 万行，放进一个同步事务
     * 足以拖垮主库，而调用方只是点了一次按钮。<b>超限整体拒绝、不落任何行、提示分批。</b>
     */
    public static final int MAX_ROWS_PER_CALL = 5000;

    /** 一条 INSERT 里最多拼多少行 —— 太大时单条 SQL 报文过长。 */
    private static final int INSERT_CHUNK = 500;

    /** {@code duplicated} 明细最多返回前 100 条（§9.2 响应字段说明）。 */
    private static final int DUPLICATED_SAMPLE_LIMIT = 100;

    private static final int NODE_STATUS_DISABLED = 1;

    private final OrgResourceGrantMapper grantMapper;
    private final OrgNodeMapper nodeMapper;
    private final ResourceOwnerChecker ownerChecker;
    private final GrantableResourceReader grantableReader;
    private final SubtreeScopeHelper subtreeScope;
    private final CurrentNodeProvider currentNodeProvider;
    private final NodeNameReader nodeNameReader;

    public GrantWriteService(OrgResourceGrantMapper grantMapper,
                             OrgNodeMapper nodeMapper,
                             ResourceOwnerChecker ownerChecker,
                             GrantableResourceReader grantableReader,
                             SubtreeScopeHelper subtreeScope,
                             CurrentNodeProvider currentNodeProvider,
                             NodeNameReader nodeNameReader) {
        this.grantMapper = grantMapper;
        this.nodeMapper = nodeMapper;
        this.ownerChecker = ownerChecker;
        this.grantableReader = grantableReader;
        this.subtreeScope = subtreeScope;
        this.currentNodeProvider = currentNodeProvider;
        this.nodeNameReader = nodeNameReader;
    }

    /**
     * 授权。<b>任一校验失败即整体回滚，不做部分成功</b>（§9.2 校验表开头逐字）。
     *
     * <p>校验顺序照 §9.2 的表，一条不换位置：
     * <ol>
     *   <li>资源都在「我可授权的资源列表」内 → 否则 {@code 10301}；
     *   <li>目标节点存在且未删除 → 否则 {@code 10101}；
     *   <li>目标节点都在我的<b>子树</b>内 → 否则 {@code 10302}；
     *   <li>目标节点未停用 → 否则 {@code 10109}；
     *   <li>{@code resourceType ∈ {2,3}} 时目标不得是学生节点 → 否则 {@code 10308}；
     *   <li>{@code validEnd} 超出我自己的 → <b>截断，不报错</b>；
     *   <li>不存在重复授权 → 否则 {@code 10303}（或按 {@code ignoreDuplicate} 跳过）。
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public GrantCreatedVO grant(GrantCreateReq req) {
        Long myNodeId = currentNodeProvider.requireCurrentNodeId();
        ResourceType type = ResourceType.of(req.getResourceType()).orElseThrow();

        List<Long> resourceIds = distinct(req.getResourceIds());
        List<Long> targetNodeIds = distinct(req.getTargetNodeIds());

        assertWithinRowLimit(resourceIds, targetNodeIds);
        assertValidPeriod(req);

        assertAllRegrantable(type, resourceIds, myNodeId);
        assertTargetsAcceptable(type, targetNodeIds, myNodeId);

        Map<Long, LocalDateTime> caps = truncationCaps(type, resourceIds, myNodeId);

        Set<String> existing = existingPairs(type, resourceIds, targetNodeIds);
        if (!existing.isEmpty() && !req.ignoreDuplicate()) {
            // 整批回滚，不做部分成功 —— 防止误覆盖已有有效期（§9.2「重复授权的两种处理」）
            throw new BizException(ErrorCode.GRANT_ALREADY_EXISTS);
        }

        LocalDateTime now = LocalDateTime.now();
        List<OrgResourceGrant> rows = new ArrayList<>();
        List<String> duplicatedPairs = new ArrayList<>();
        boolean truncated = false;
        LocalDateTime strictestEnd = null;

        for (Long resourceId : resourceIds) {
            LocalDateTime effectiveEnd = capOf(req.getValidEnd(), caps.get(resourceId));
            if (!java.util.Objects.equals(effectiveEnd, req.getValidEnd())) {
                truncated = true;
            }
            if (effectiveEnd != null && (strictestEnd == null || effectiveEnd.isBefore(strictestEnd))) {
                strictestEnd = effectiveEnd;
            }
            for (Long targetNodeId : targetNodeIds) {
                String pair = pairKey(resourceId, targetNodeId);
                if (existing.contains(pair)) {
                    duplicatedPairs.add(pair);
                    continue;
                }
                OrgResourceGrant row = new OrgResourceGrant();
                row.setId(IdWorker.nextId());
                row.setResourceType(type.code());
                row.setResourceId(resourceId);
                row.setTargetNodeId(targetNodeId);
                row.setValidStart(req.getValidStart());
                row.setValidEnd(effectiveEnd);
                row.setGrantSource(req.grantSourceOrDefault());
                row.setSourceRefId(req.getSourceRefId());
                rows.add(row);
            }
        }

        Long operatorId = TenantHelper.getUserId();
        for (int from = 0; from < rows.size(); from += INSERT_CHUNK) {
            grantMapper.insertBatch(rows.subList(from, Math.min(from + INSERT_CHUNK, rows.size())),
                    operatorId);
        }

        GrantCreatedVO vo = new GrantCreatedVO();
        vo.setResourceType(type.code());
        vo.setResourceCount(resourceIds.size());
        vo.setTargetNodeCount(targetNodeIds.size());
        vo.setGrantedCount(rows.size());
        vo.setDuplicatedCount(duplicatedPairs.size());
        vo.setDuplicated(describeDuplicates(type, duplicatedPairs));
        vo.setValidStart(req.getValidStart());
        vo.setValidEnd(req.getValidEnd());
        vo.setValidEndTruncated(truncated);
        vo.setEffectiveValidEnd(strictestEnd);
        vo.setGrantSource(req.grantSourceOrDefault());
        vo.setGrantTime(now);
        return vo;
    }

    // =====================================================================
    // 参数层：DB 一行都不碰
    // =====================================================================

    /**
     * 总量硬上限：<b>资源数 × 目标节点数 ≤ {@value #MAX_ROWS_PER_CALL}</b>。
     *
     * <h2>是乘积，不是任一侧的条数</h2>
     * <p>两个数组各自的 500 上限由 DTO 的 {@code @Size} 管，<b>那两条拦不住这一条</b>：
     * 500 × 20 = 10000 行，两侧都合法而乘积翻倍超限。
     * 只判任一侧的写法会<b>照样放行</b>，而后果是一个同步事务里一万行写入。
     *
     * <h2>为什么在去重之后算</h2>
     * <p>唯一键是 {@code (resource_type, resource_id, target_node_id)}，重复的 ID
     * 不会变成第二行。上限管的是「<b>单次写入的授权行数</b>」，所以基数必须是去重后的。
     *
     * <p>本判定在<b>任何一次查库之前</b>完成 —— 超限时 DB 一行都不碰，
     * 而不是先查了一圈再拒。
     */
    private static void assertWithinRowLimit(List<Long> resourceIds, List<Long> targetNodeIds) {
        long rows = (long) resourceIds.size() * targetNodeIds.size();
        if (rows > MAX_ROWS_PER_CALL) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单次授权行数 " + rows + " 超过上限 " + MAX_ROWS_PER_CALL
                            + "（资源数 " + resourceIds.size() + " × 目标节点数 "
                            + targetNodeIds.size() + "），请分批提交");
        }
    }

    /** {@code validStart} 必须早于 {@code validEnd}（§9.2 校验表第 6 条，返回 400）。 */
    private static void assertValidPeriod(GrantCreateReq req) {
        LocalDateTime start = req.getValidStart();
        LocalDateTime end = req.getValidEnd();
        if (start != null && end != null && !start.isBefore(end)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "生效时间必须早于失效时间");
        }
    }

    // =====================================================================
    // 校验 1：资源拥有性 → 10301
    // =====================================================================

    /**
     * 每个资源都必须<b>可再下发</b>（契约 §2.5 规则 1 + 规则 9）。
     *
     * <h2>⚠ 响应<b>不区分</b>「资源不存在」与「你无权」</h2>
     * <p>契约 §2.5 规则 1、PRD FR-1 规则 2：两种情况回<b>同一个</b> {@code 10301}，
     * <b>且不指出是哪一个资源</b>。指出来就等于确认「这个 ID 存在」——
     * 攻击者拿一批 ID 挨个试，能凭「报的是哪一个」把别人的资源清单枚举出来。
     * 与 F-42 是同一条推理。
     *
     * <h2>为什么用 {@code canRegrant} 而不是 {@code canUse}</h2>
     * <p>跨管辖授权仍可<b>使用</b>但丧失<b>再下发</b>能力（契约 §2.5 规则 9）。
     * 这里与接口 37 的清单用的是<b>同一个</b> {@code regrantableIds} ——
     * 列表滤掉的这里必须拒，反过来也一样，否则界面与后端各说一套。
     */
    private void assertAllRegrantable(ResourceType type, List<Long> resourceIds, Long myNodeId) {
        Set<Long> regrantable = ownerChecker.regrantableIds(type, resourceIds, myNodeId);
        if (regrantable.size() != resourceIds.size()) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND_OR_NO_GRANT_RIGHT);
        }
    }

    // =====================================================================
    // 校验 2~5：目标节点 → 10101 / 10302 / 10109 / 10308
    // =====================================================================

    private void assertTargetsAcceptable(ResourceType type, List<Long> targetNodeIds,
                                         Long myNodeId) {
        Map<Long, OrgNode> nodes = new LinkedHashMap<>();
        nodeMapper.selectByIds(targetNodeIds).forEach(node -> nodes.put(node.getId(), node));

        boolean studentForbidden = type == ResourceType.QUESTION || type == ResourceType.VIDEO;
        for (Long targetNodeId : targetNodeIds) {
            OrgNode node = nodes.get(targetNodeId);
            if (node == null) {
                // 不存在、已删除、跨租户（插件过滤掉）三者同一个出口
                throw new BizException(ErrorCode.NODE_NOT_FOUND, null, targetNodeId, null);
            }
            if (!subtreeScope.isInSubtree(myNodeId, targetNodeId)) {
                // 【10302 不是 10107】：契约 §2.4 把「请求体里的目标越界」定为 10107，
                // 而 03-02 §9.2/§9.3/§9.4 与错误码登记册把 10302 逐字绑给接口 38/39/40/50。
                // 同层互相打架，本模块取 10302（登记册为它专门开了一个码，三个接口的
                // 「相关业务错误码」栏也都写着它）。已按「明知地推翻」登记
                throw new BizException(ErrorCode.GRANT_TARGET_OUT_OF_SUBTREE, null, targetNodeId, null);
            }
            if (node.getStatus() != null && node.getStatus() == NODE_STATUS_DISABLED) {
                throw new BizException(ErrorCode.NODE_DISABLED, null, targetNodeId, null);
            }
            if (studentForbidden && node.getNodeType() != null
                    && node.getNodeType() == NodePath.NODE_TYPE_STUDENT) {
                // 契约 §2.5 规则 11：学生侧【没有】题目/视频的直接使用入口 ——
                // 作答走 hw_homework_target + 固化版本，播放走课程授权，错题本走版本快照。
                // 授给学生的行【永远不会被任何鉴权路径读到】，只会放大授权表并污染悬挂巡检，
                // 而 grant_dangling_count 的告警线是 > 0
                throw new BizException(ErrorCode.RESOURCE_TYPE_NOT_GRANTABLE_TO_STUDENT,
                        null, targetNodeId, null);
            }
        }
    }

    // =====================================================================
    // 校验 6：有效期截断（不报错）
    // =====================================================================

    /**
     * 每个资源各自的截断上界 = <b>我自己持有该资源的</b> {@code valid_end}。
     *
     * <p>契约 §2.5 规则 7：「授权人自身为 {@code owner_node_id} 时<b>不受此限</b>」，
     * 故 owner 的资源在返回的 Map 里<b>没有键</b>（不是 {@code null} 值 ——
     * 那会和「持有但永久有效」混成同一个状态）。
     */
    private Map<Long, LocalDateTime> truncationCaps(ResourceType type, List<Long> resourceIds,
                                                    Long myNodeId) {
        Map<Long, LocalDateTime> caps = new LinkedHashMap<>();
        List<Long> granted = resourceIds.stream()
                .filter(id -> !ownerChecker.isOwner(type, id, myNodeId))
                .toList();
        if (granted.isEmpty()) {
            return caps;
        }
        for (OrgResourceGrant row : grantMapper.selectList(new LambdaQueryWrapper<OrgResourceGrant>()
                .eq(OrgResourceGrant::getResourceType, type.code())
                .eq(OrgResourceGrant::getTargetNodeId, myNodeId)
                .in(OrgResourceGrant::getResourceId, granted))) {
            if (row.getValidEnd() != null) {
                caps.put(row.getResourceId(), row.getValidEnd());
            }
        }
        return caps;
    }

    /** 取「请求值」与「我的上界」中<b>更早</b>的那个；上界不存在时按请求值原样。 */
    private static LocalDateTime capOf(LocalDateTime requested, LocalDateTime cap) {
        if (cap == null) {
            return requested;
        }
        if (requested == null || requested.isAfter(cap)) {
            return cap;
        }
        return requested;
    }

    // =====================================================================
    // 校验 7：重复授权 → 10303
    // =====================================================================

    /** 已存在（{@code deleted_at = 0}）的 {@code (资源, 目标)} 组合。 */
    private Set<String> existingPairs(ResourceType type, List<Long> resourceIds,
                                      List<Long> targetNodeIds) {
        Set<String> pairs = new LinkedHashSet<>();
        for (OrgResourceGrant row : grantMapper.selectList(new LambdaQueryWrapper<OrgResourceGrant>()
                .eq(OrgResourceGrant::getResourceType, type.code())
                .in(OrgResourceGrant::getResourceId, resourceIds)
                .in(OrgResourceGrant::getTargetNodeId, targetNodeIds))) {
            pairs.add(pairKey(row.getResourceId(), row.getTargetNodeId()));
        }
        return pairs;
    }

    private List<DuplicatedGrantVO> describeDuplicates(ResourceType type, List<String> pairs) {
        if (pairs.isEmpty()) {
            return List.of();
        }
        List<String> sample = pairs.subList(0, Math.min(DUPLICATED_SAMPLE_LIMIT, pairs.size()));
        Set<Long> resourceIds = new LinkedHashSet<>();
        Set<Long> nodeIds = new LinkedHashSet<>();
        sample.forEach(pair -> {
            resourceIds.add(resourceOf(pair));
            nodeIds.add(nodeOf(pair));
        });
        Map<Long, String> resourceNames = grantableReader.namesOf(type, resourceIds);
        Map<Long, String> nodeNames = nodeNameReader.nodeNames(nodeIds);
        Map<String, LocalDateTime> ends = new LinkedHashMap<>();
        grantMapper.selectList(new LambdaQueryWrapper<OrgResourceGrant>()
                        .eq(OrgResourceGrant::getResourceType, type.code())
                        .in(OrgResourceGrant::getResourceId, resourceIds)
                        .in(OrgResourceGrant::getTargetNodeId, nodeIds))
                .forEach(row -> ends.put(pairKey(row.getResourceId(), row.getTargetNodeId()),
                        row.getValidEnd()));

        List<DuplicatedGrantVO> list = new ArrayList<>(sample.size());
        for (String pair : sample) {
            DuplicatedGrantVO vo = new DuplicatedGrantVO();
            vo.setResourceId(resourceOf(pair));
            vo.setResourceName(resourceNames.get(resourceOf(pair)));
            vo.setTargetNodeId(nodeOf(pair));
            vo.setTargetNodeName(nodeNames.get(nodeOf(pair)));
            vo.setExistingValidEnd(ends.get(pair));
            list.add(vo);
        }
        return list;
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    /** 去重且<b>保序</b> —— 响应里的计数与明细顺序要能对着请求看。 */
    private static List<Long> distinct(List<Long> ids) {
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    /** {@code (资源, 目标)} 的组合键。用字符串拼接，不做数值合成 —— 那是哈希不是唯一键。 */
    private static String pairKey(Long resourceId, Long targetNodeId) {
        return resourceId + ":" + targetNodeId;
    }

    private static Long resourceOf(String pair) {
        return Long.valueOf(pair.substring(0, pair.indexOf(':')));
    }

    private static Long nodeOf(String pair) {
        return Long.valueOf(pair.substring(pair.indexOf(':') + 1));
    }
}
