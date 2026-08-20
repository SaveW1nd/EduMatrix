package com.edumatrix.org.grant.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.resource.GrantableResourceReader;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.grant.dto.GrantRevokeReq;
import com.edumatrix.org.grant.mapper.GrantCascadeMapper;
import com.edumatrix.org.grant.vo.CascadeDetailVO;
import com.edumatrix.org.grant.vo.CascadeNodeVO;
import com.edumatrix.org.grant.vo.GrantRevokedVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;

/**
 * 接口 39 撤销资源授权（<b>级联子树，强制，无开关</b>）（03-02 §9.3、契约 §2.5 规则 5）。
 *
 * <h2>事务边界：怎么保证「要么全撤要么不撤」</h2>
 * <pre>
 * @Transactional                                  ← 一个事务，覆盖全部 (资源 × 目标) 组合
 *   ① 按 id 升序 FOR UPDATE 锁全部目标节点        ← 与 NodeMoveService 【同一个加锁顺序】，
 *      （顺带就是 10101 的存在性判定）                反过来必然与移动事务形成死锁
 *   ② 逐 (资源 × 目标)：先 COUNT + 取样本，再 UPDATE
 *   ③ affected != COUNT → 抛异常，【整个事务回滚，一行不落】
 * </pre>
 * <p><b>原子性靠事务本身，不靠「先检查再执行」。</b> ②③ 只是让失败发生在提交之前；
 * 真正保证「要么全撤要么不撤」的是<b>单个事务 + 任何异常整体回滚</b>。
 *
 * <h2>⚠ 撤销<b>不受</b> 5000 行上限约束（F-84，明知地偏离 04 §B 规则 14）</h2>
 * <p>04 §B 规则 14 写「单次写入的授权行数 ≤ 5000，超限整体拒绝」，而契约 §2.5 那条 5000
 * 的语境是<b>批量授权与模板套用</b>。撤销的行数由<b>子树规模</b>决定，调用方控制不了：
 * 单资源单节点的撤销若子树超 5000 行，硬拦会造出一个<b>撤不掉又无法分批</b>的死角
 * ——级联是强制的，不能只撤一半 —— 结果是<b>永久悬挂</b>，
 * 而 {@code grant_dangling_count} 的告警线是 <b>&gt; 0</b>。
 *
 * <p><b>长事务是性能问题，永久悬挂是正确性问题。</b> 故本实现不拦，
 * 超过 {@value #ROWS_WARN_THRESHOLD} 行记 WARN（告警而不截断、更不拒绝）。
 * 需方已定案按此实现，可推翻。
 */
@Service
public class GrantRevokeService {

    private static final Logger log = LoggerFactory.getLogger(GrantRevokeService.class);

    /** {@code cascadeNodes[]} 样本上限（§9.3 响应字段说明「最多返回前 50 个」）。 */
    private static final int CASCADE_SAMPLE_LIMIT = 50;

    /** 单次撤销行数的<b>告警</b>线（不是拒绝线，见类注释 F-84）。 */
    static final int ROWS_WARN_THRESHOLD = 5000;

    private static final int NODE_TYPE_STUDENT = 3;

    private final GrantCascadeMapper cascadeMapper;
    private final OrgNodeMapper nodeMapper;
    private final GrantableResourceReader grantableReader;
    private final SubtreeScopeHelper subtreeScope;
    private final CurrentNodeProvider currentNodeProvider;

    public GrantRevokeService(GrantCascadeMapper cascadeMapper,
                              OrgNodeMapper nodeMapper,
                              GrantableResourceReader grantableReader,
                              SubtreeScopeHelper subtreeScope,
                              CurrentNodeProvider currentNodeProvider) {
        this.cascadeMapper = cascadeMapper;
        this.nodeMapper = nodeMapper;
        this.grantableReader = grantableReader;
        this.subtreeScope = subtreeScope;
        this.currentNodeProvider = currentNodeProvider;
    }

    /**
     * 撤销。<b>方法签名里没有、也不会有「要不要级联」的参数</b>（见 {@code GrantRevokeReq}）。
     *
     * <p><b>幂等</b>：对不存在或已撤销的组合重复调用返回 {@code 200}、{@code revokedCount = 0}
     *（§9.3「幂等」段、00-通用约定 §7.1 DELETE 幂等）。
     */
    @Transactional(rollbackFor = Exception.class)
    public GrantRevokedVO revokeCascade(GrantRevokeReq req) {
        Long myNodeId = currentNodeProvider.requireCurrentNodeId();
        ResourceType type = ResourceType.of(req.getResourceType()).orElseThrow();
        List<Long> resourceIds = distinct(req.getResourceIds());
        List<Long> targetNodeIds = distinct(req.getTargetNodeIds());

        Map<Long, OrgNode> locked = lockTargets(targetNodeIds, myNodeId);
        Map<Long, String> resourceNames = grantableReader.namesOf(type, resourceIds);
        Long operatorId = TenantHelper.getUserId();

        // 影响面【必须在 UPDATE 之前】统计：撤完之后 deleted_at 不再为 0，什么都查不到了。
        // 跨目标做【集合并】而不是把各目标的 COUNT 相加 —— 目标之间可能嵌套（同时撤 A1 与
        // A1 名下的 T1 是合法请求），相加会把同一个节点数两次
        Set<Long> affectedNodes = new LinkedHashSet<>();
        Set<Long> affectedStudents = new LinkedHashSet<>();
        for (Long targetNodeId : targetNodeIds) {
            String prefix = subtreePrefix(locked.get(targetNodeId));
            cascadeMapper.selectAffectedNodes(type.code(), resourceIds, targetNodeId, prefix)
                    .forEach(row -> {
                        affectedNodes.add(row.getNodeId());
                        if (row.getNodeType() != null && row.getNodeType() == NODE_TYPE_STUDENT) {
                            affectedStudents.add(row.getNodeId());
                        }
                    });
        }

        int total = 0;
        int direct = 0;
        List<CascadeDetailVO> details = new ArrayList<>();
        for (Long targetNodeId : targetNodeIds) {
            OrgNode node = locked.get(targetNodeId);
            String prefix = subtreePrefix(node);
            for (Long resourceId : resourceIds) {
                int expected = cascadeMapper.countSubtreeGrants(
                        type.code(), resourceId, targetNodeId, prefix);
                if (expected == 0) {
                    continue;   // 幂等：不存在或已撤销的组合什么都不做
                }
                int directRows = cascadeMapper.countDirectGrant(type.code(), resourceId, targetNodeId);
                details.add(describe(type, resourceId, targetNodeId, prefix, node,
                        resourceNames, expected - directRows));

                int affected = cascadeMapper.revokeSubtree(type.code(), resourceId, targetNodeId,
                        prefix, operatorId, req.getReason());
                if (affected != expected) {
                    // 并发保护：COUNT 与 UPDATE 之间有人插了新的授权行（或撤了）。
                    // 【抛出 = 整个事务回滚 = 一行不落】，而不是「按实际撤到的算」——
                    // 后者会让「要么全撤要么不撤」变成「撤了一部分且没人知道」
                    throw new IllegalStateException(
                            "级联撤销的行数与预计数不符（预计 " + expected + "、实际 " + affected
                                    + "），存在并发写入，本次整体回滚：resourceType=" + type.code()
                                    + " resourceId=" + resourceId + " targetNodeId=" + targetNodeId);
                }
                total += affected;
                direct += directRows;
            }
        }

        if (total > ROWS_WARN_THRESHOLD) {
            // F-84：【告警而不拒绝】。硬拦会造出「撤不掉又无法分批」的死角 → 永久悬挂，
            // 而 grant_dangling_count 的告警线是 > 0。长事务是性能问题，永久悬挂是正确性问题
            log.warn("单次级联撤销 {} 行，超过告警线 {}（不拒绝，见 F-84）：operator={} "
                            + "resourceType={} 资源 {} 个、目标节点 {} 个",
                    total, ROWS_WARN_THRESHOLD, operatorId, type.code(),
                    resourceIds.size(), targetNodeIds.size());
        }

        GrantRevokedVO vo = new GrantRevokedVO();
        vo.setResourceType(type.code());
        vo.setResourceCount(resourceIds.size());
        vo.setTargetNodeCount(targetNodeIds.size());
        vo.setRevokedCount(total);
        vo.setDirectRevokedCount(direct);
        vo.setCascadeRevokedCount(total - direct);
        vo.setAffectedNodeCount(affectedNodes.size());
        vo.setAffectedStudentCount(affectedStudents.size());
        vo.setCascadeDetail(details);
        vo.setLearningRecordsRetained(true);
        vo.setRevokeTime(LocalDateTime.now());
        return vo;
    }

    /** 一个 {@code (资源, 目标)} 组合的披露行：样本最多 50 个，完整数量另给。 */
    private CascadeDetailVO describe(ResourceType type, Long resourceId, Long targetNodeId,
                                     String prefix, OrgNode node, Map<Long, String> resourceNames,
                                     int cascadeNodeCount) {
        CascadeDetailVO detail = new CascadeDetailVO();
        detail.setResourceId(resourceId);
        detail.setResourceName(resourceNames.get(resourceId));
        detail.setTargetNodeId(targetNodeId);
        detail.setTargetNodeName(node.getNodeName());
        detail.setCascadeNodeCount(cascadeNodeCount);
        detail.setCascadeNodes(cascadeMapper
                .selectCascadeNodes(type.code(), resourceId, targetNodeId, prefix,
                        CASCADE_SAMPLE_LIMIT)
                .stream()
                .map(row -> new CascadeNodeVO(row.getNodeId(), row.getNodeName(), row.getNodeType()))
                .toList());
        return detail;
    }

    private String subtreePrefix(OrgNode node) {
        return node.getAncestors() == null || node.getAncestors().isEmpty()
                ? String.valueOf(node.getId())
                : node.getAncestors() + "," + node.getId();
    }

    private static List<Long> distinct(List<Long> ids) {
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    /**
     * 步骤 ①：按 <b>id 升序</b>对全部目标节点加行锁，顺带完成 {@code 10101} / {@code 10302}。
     *
     * <p><b>为什么撤销也要锁 {@code org_node}</b>（02-数据库设计 §3.3.3 步骤 1 逐字
     * 「并加锁防并发授权」）：子树前缀 {@code P} 是从 {@code ancestors} 算出来的，
     * 而节点移动会<b>重算整棵子树的 ancestors</b>。不加锁就可能出现
     * 「按旧 {@code ancestors} 算出的 P 去撤，而树已经变了」——
     * 撤到一半的行属于旧子树、另一半属于新子树，<b>而且不报错</b>。
     *
     * <p><b>{@code ORDER BY id} 不是可选的</b>：与 {@code NodeMoveService} 走同一条
     * {@code selectForUpdateOrderById}，两个事务的加锁顺序因此一致。
     * 反过来（这里乱序）会与移动事务形成环，实测过的死锁形态见那个 Mapper 的注释。
     */
    private Map<Long, OrgNode> lockTargets(List<Long> targetNodeIds, Long myNodeId) {
        Map<Long, OrgNode> locked = new LinkedHashMap<>();
        nodeMapper.selectForUpdateOrderById(targetNodeIds).forEach(n -> locked.put(n.getId(), n));

        for (Long targetNodeId : targetNodeIds) {
            if (!locked.containsKey(targetNodeId)) {
                // 不存在 / 已删除 / 跨租户三者同一个出口，不暴露存在性
                throw new BizException(ErrorCode.NODE_NOT_FOUND, null, targetNodeId, null);
            }
            if (!subtreeScope.isInSubtree(myNodeId, targetNodeId)) {
                throw new BizException(ErrorCode.GRANT_TARGET_OUT_OF_SUBTREE, null, targetNodeId, null);
            }
        }
        return locked;
    }
}
