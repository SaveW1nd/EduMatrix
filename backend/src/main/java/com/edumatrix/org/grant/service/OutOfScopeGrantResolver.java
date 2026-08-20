package com.edumatrix.org.grant.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.edumatrix.common.resource.GrantableResourceReader;
import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.grant.mapper.GrantCascadeMapper;
import com.edumatrix.org.grant.mapper.OutOfScopeGrantMapper;
import com.edumatrix.org.node.vo.OutOfScopeGrantVO;

/**
 * 节点移动的<b>跨管辖授权</b>：算清单 + （可选）级联回收 ——
 * 模块 06 显式留给本模块的那个接口（契约 §2.5 规则 9、03-02 §3.4）。
 *
 * <h2>模块 06 只做了可算的那一半</h2>
 * <p>它按 §3.4 的措辞判「<b>由原上级授予</b>」= {@code grant_by} 所在节点移动后不再是祖先。
 * 而契约 §2.5 规则 9 的完整判据是：
 * 「授权行的 {@code target_node_id} 当前祖先链<b>不再包含</b>该资源 {@code owner_node_id}
 * <b>或其有效授权链</b>」—— 那要读三张资源表的 {@code owner_node_id}，
 * 不在模块 06 的涉及表里。本类补齐这一半。
 *
 * <h2>判定<b>不新写</b>，直接复用 {@code canRegrant}</h2>
 * <p>「链断没断」正是 {@code ResourceOwnerChecker.canRegrant} 已经在答的问题：
 * 对一条<b>持有该资源</b>的行，{@code canRegrant == false} ⟺ 从 owner 到它的链断了
 * ⟺ 跨管辖（只读）。再写一遍判定就是第二份实现，而<b>两份都返回 200</b>。
 *
 * <p><b>批量而不是逐行</b>：按 {@code (资源类型, 目标节点)} 分组，
 * 一组一次 {@code regrantableIds}。逐行调是 3N 次往返，
 * 而移动一个大分支时 N 可以是几千 —— 慢，但<b>不报错</b>。
 *
 * <h2>已知的过报，如实写下来</h2>
 * <p>本判定对<b>移动之前就已经悬挂</b>的行也会判为「跨管辖」——
 * 因为它们的链<b>确实</b>是断的。那不是误报（它们确实在新上级的管辖之外），
 * 只是成因不是这次移动。两者的区分是巡检任务的事（{@code dangling} vs {@code crossScope}，
 * 契约 §2.5 规则 6），不是移动响应的事。
 */
@Service
public class OutOfScopeGrantResolver {

    private static final Logger log = LoggerFactory.getLogger(OutOfScopeGrantResolver.class);

    private final OutOfScopeGrantMapper outOfScopeMapper;
    private final GrantCascadeMapper cascadeMapper;
    private final ResourceOwnerChecker ownerChecker;
    private final GrantableResourceReader grantableReader;

    public OutOfScopeGrantResolver(OutOfScopeGrantMapper outOfScopeMapper,
                                   GrantCascadeMapper cascadeMapper,
                                   ResourceOwnerChecker ownerChecker,
                                   GrantableResourceReader grantableReader) {
        this.outOfScopeMapper = outOfScopeMapper;
        this.cascadeMapper = cascadeMapper;
        this.ownerChecker = ownerChecker;
        this.grantableReader = grantableReader;
    }

    /**
     * 算出被移动子树内<b>已跨出管辖范围</b>的授权清单。
     *
     * <p><b>必须在 {@code ancestors} 重算之后、移动事务内调用</b>。
     */
    public List<OutOfScopeGrantRow> collect(Long movingNodeId, String newPrefix) {
        List<OutOfScopeGrantMapper.SubtreeGrantRow> rows =
                outOfScopeMapper.selectSubtreeGrants(movingNodeId, newPrefix);
        if (rows.isEmpty()) {
            return List.of();
        }

        // 按 (资源类型, 目标节点) 分组，一组一次批量判定
        Map<ResourceType, Map<Long, Set<Long>>> grouped = new EnumMap<>(ResourceType.class);
        Map<Long, String> ancestorsOf = new LinkedHashMap<>();
        for (OutOfScopeGrantMapper.SubtreeGrantRow row : rows) {
            ancestorsOf.put(row.getTargetNodeId(), row.getTargetAncestors());
            ResourceType.of(row.getResourceType()).ifPresent(type ->
                    grouped.computeIfAbsent(type, k -> new LinkedHashMap<>())
                            .computeIfAbsent(row.getTargetNodeId(), k -> new LinkedHashSet<>())
                            .add(row.getResourceId()));
        }

        // 【必须传 SQL 刚读到的 ancestors，不能让 canRegrant 去查缓存】
        // 本方法跑在【移动事务内】：树已经改了，而 node:anc: 缓存要等提交后才失效
        //（契约 §2.3 —— 提交前清会让别的连接把旧值写回，那时缓存永远错下去）。
        // 走缓存版的实测后果：被移动的教师用【移动前】的链判定，旧上级仍持有该资源、
        // 于是判成「链完整」而【没有进跨管辖清单】；而他名下的学员没被缓存过、
        // 走了库里的新值反倒判对了 —— 表现是「清单里有 8 名学员、独独漏掉那个教师」，
        // 接口返回 200。这个形态本模块实测踩到过，用例 trueActuallyRevokes 钉住它
        Map<ResourceType, Map<Long, Set<Long>>> regrantable = new EnumMap<>(ResourceType.class);
        grouped.forEach((type, byNode) -> {
            Map<Long, Set<Long>> perNode = new LinkedHashMap<>();
            byNode.forEach((nodeId, resourceIds) -> perNode.put(nodeId,
                    ownerChecker.regrantableIds(type, resourceIds, nodeId, ancestorsOf.get(nodeId))));
            regrantable.put(type, perNode);
        });

        List<OutOfScopeGrantRow> outOfScope = new ArrayList<>();
        for (OutOfScopeGrantMapper.SubtreeGrantRow row : rows) {
            ResourceType type = ResourceType.of(row.getResourceType()).orElse(null);
            if (type == null) {
                continue;
            }
            Set<Long> ok = regrantable.getOrDefault(type, Map.of())
                    .getOrDefault(row.getTargetNodeId(), Set.of());
            if (ok.contains(row.getResourceId())) {
                continue;   // 链完整 —— 管辖关系没变
            }
            outOfScope.add(new OutOfScopeGrantRow(type, row.getResourceId(), row.getTargetNodeId(),
                    row.getTargetNodeName(), row.getTargetAncestors()));
        }
        return outOfScope;
    }

    /**
     * 把清单转成响应 VO（补上资源名 —— 模块 06 那里<b>恒为 null</b> 的那个字段）。
     */
    public List<OutOfScopeGrantVO> toVo(List<OutOfScopeGrantRow> rows) {
        Map<ResourceType, Set<Long>> byType = new EnumMap<>(ResourceType.class);
        rows.forEach(row -> byType.computeIfAbsent(row.type(), k -> new LinkedHashSet<>())
                .add(row.resourceId()));
        Map<ResourceType, Map<Long, String>> names = new EnumMap<>(ResourceType.class);
        byType.forEach((type, ids) -> names.put(type, grantableReader.namesOf(type, ids)));

        List<OutOfScopeGrantVO> list = new ArrayList<>(rows.size());
        for (OutOfScopeGrantRow row : rows) {
            OutOfScopeGrantVO vo = new OutOfScopeGrantVO();
            vo.setResourceType(row.type().code());
            vo.setResourceId(row.resourceId());
            vo.setResourceName(names.getOrDefault(row.type(), Map.of()).get(row.resourceId()));
            vo.setTargetNodeId(row.targetNodeId());
            vo.setTargetNodeName(row.targetNodeName());
            list.add(vo);
        }
        return list;
    }

    /**
     * <b>级联</b>回收清单里的授权（{@code revokeOutOfScopeGrants = true} 时调用）。
     *
     * <h2>级联在<b>今天</b>是冗余的，保留它是为了明天 —— 这一点必须说准</h2>
     * <p>我起初写的理由是「教师 T 跨管辖、而 T 名下学员 S 的链经过 T 仍然完整，
     * 于是 S 不在清单里，只撤清单会让 S 变成悬挂」。<b>那个场景在当前语义下不可能发生</b>：
     * {@code chainIntact} 取的是<b>链</b>读法（从 owner 那一层起往下每一层都必须持有），
     * 于是链上任何一层断了，<b>它以下的每一个后代都同时断</b> ——
     * T 跨管辖时 S 必然也在清单里。清单本身<b>已经对后代封闭</b>。
     *
     * <p><b>实测确认</b>：把这里的子树条件去掉（只撤清单里那些行），
     * {@code NodeMoveOutOfScopeGrantIT} 四条<b>全绿</b> ——
     * 也就是说<b>当前没有任何一条用例能区分这两种写法</b>。如实写在这里。
     *
     * <p>那为什么还留着：{@code chainIntact} 的读法是 <b>F-81 登记过的一个取舍</b>，
     * 备选是「链上存在任意持有者」。一旦改成备选读法，清单就<b>不再对后代封闭</b>，
     * 而那时缺了级联的表现是<b>静默留下悬挂授权</b>。
     * 级联的成本是零（已撤的行 {@code deleted_at != 0}，WHERE 里就排除了，重复覆盖幂等），
     * 用零成本换掉一整类静默故障是划算的。
     *
     * <p>走的是与接口 39 <b>同一条</b> {@code GrantCascadeMapper#revokeSubtree}，
     * 不另写一份撤销。
     *
     * @return 实际撤销的行数
     */
    public int revokeCascade(List<OutOfScopeGrantRow> rows, String reason) {
        if (rows.isEmpty()) {
            return 0;
        }
        Long operatorId = TenantHelper.getUserId();
        int revoked = 0;
        for (OutOfScopeGrantRow row : rows) {
            String prefix = row.targetAncestors() == null || row.targetAncestors().isEmpty()
                    ? String.valueOf(row.targetNodeId())
                    : row.targetAncestors() + "," + row.targetNodeId();
            revoked += cascadeMapper.revokeSubtree(row.type().code(), row.resourceId(),
                    row.targetNodeId(), prefix, operatorId, reason);
        }
        log.info("节点移动附带回收跨管辖授权 {} 行（revokeOutOfScopeGrants=true，契约 §2.5 规则 9）",
                revoked);
        return revoked;
    }

    /** 一条跨管辖授权（内部形态，带级联回收要用的 {@code targetAncestors}）。 */
    public record OutOfScopeGrantRow(ResourceType type, Long resourceId, Long targetNodeId,
                                     String targetNodeName, String targetAncestors) {
    }
}
