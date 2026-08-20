package com.edumatrix.common.grant;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.edumatrix.common.grant.mapper.ResourceGrantMapper;
import com.edumatrix.common.resource.ResourceType;

/**
 * 资源授权的<b>读侧</b>唯一口径（契约 §2.5 规则 4）——「某节点能否使用某资源」。
 *
 * <h2>为什么是永久的公共构件，而不是模块 11 的一部分</h2>
 * <p>与 {@code common/subtree/SubtreeScopeHelper} <b>完全同型</b>：那个类也是从
 * {@code common/} 直读 {@code org_node}（{@code org} 领域的表），理由是
 * 「你能看到哪些数据」是<b>全系统唯一口径</b>，散到各领域就会变成 N 种写法。
 * 资源可见性（契约 §2.5 规则 4）是同一种东西：模块 08 / 10 / 11 / 12 / 13 / 14 / 15 / 16
 * 都要问同一个问题，而分散实现的后果是「有的地方回溯了祖先链、有的地方没回溯」——
 * 且两种写法都返回 200，不会报错。
 *
 * <p><b>模块 11 只做写侧</b>（授权 / 撤销 / 级联回收 / 有效期截断），读侧复用本类。
 * 这条已写进 {@code 04-实施计划.md} §B 模块 11 的「做完什么算做完」。
 *
 * <h2>判定式：只查一条命中，绝不回溯祖先链</h2>
 * <p>契约 §2.5 规则 4 逐字：「{@code target_node_id = 我 AND resource_id = X AND 在有效期内}，
 * <b>不回溯祖先链</b>」。03-03 §0.2 又补了一句同样重要的：
 * 「上级拥有 ≠ 我自动拥有；<b>父级授权给了我的下级也不等于授权给了我</b>」。
 * 因此本类<b>没有任何一个方法接受「子树」这个概念</b> —— 传进来的
 * {@code nodeId} 永远只与 {@code target_node_id} 做<b>精确相等</b>比较。
 *
 * <p><b>唯一的例外是 {@link #grantHolders}，而它不是例外</b>：它接受的是
 * <b>祖先链</b>（向上）不是子树（向下），服务的也是另一个问题 ——
 * 「我能不能<b>再下发</b>」（契约 §2.5 规则 9），不是「我能不能<b>用</b>」（规则 4）。
 * 那个方法的注释里逐条写了为什么两者不冲突，<b>改它之前先读那一段</b>。
 *
 * <h2>有效期</h2>
 * <p>{@code valid_start IS NULL OR valid_start <= NOW()} 且
 * {@code valid_end IS NULL OR valid_end >= NOW()}（03-03 §0.2 的 SQL 逐条）。
 * 过期等同未授权（§0.2「授权过期等同未授权」）。
 *
 * <p><b>租户条件由插件注入</b>，本类一个字不写（契约 §2.9）。
 */
@Component
public class ResourceGrantReader {

    private final ResourceGrantMapper grantMapper;

    public ResourceGrantReader(ResourceGrantMapper grantMapper) {
        this.grantMapper = grantMapper;
    }

    /** 该节点当前是否被有效授权了该资源。<b>单条命中，不回溯祖先链。</b> */
    public boolean hasGrant(ResourceType type, Long resourceId, Long nodeId) {
        if (type == null || resourceId == null || nodeId == null) {
            return false;
        }
        return grantMapper.countActiveGrant(type.code(), resourceId, nodeId) > 0;
    }

    /**
     * 该节点当前被有效授权的全部资源 ID（该类型下）。
     *
     * <p>列表接口用它拼 {@code id IN (...)}，而不是在业务 Mapper 里再写一遍
     * {@code EXISTS (SELECT 1 FROM org_resource_grant ...)} —— <b>那就是第二份实现</b>。
     * 规模可控：契约 §7.1 的 {@code grant_rows_per_node} 指标把单节点授权行数的
     * P99 定在 2000，超过即告警。
     */
    public List<Long> grantedResourceIds(ResourceType type, Long nodeId) {
        if (type == null || nodeId == null) {
            return Collections.emptyList();
        }
        return grantMapper.selectActiveResourceIds(type.code(), nodeId);
    }

    /**
     * 「这些候选节点里，谁当前有效持有这些资源」—— 契约 §2.5 规则 9 的<b>链判定</b>用它。
     *
     * <h2>⚠ 唯一一个接受「一串节点」的方法，而本类的类注释说没有这种方法</h2>
     * <p>类注释那句「<b>本类没有任何一个方法接受「子树」这个概念</b>」<b>仍然成立</b>：
     * 这里传进来的是<b>祖先链</b>（向上），不是子树（向下），且它服务的是
     * <b>另一个问题</b> —— 不是「我能不能用」，而是「我能不能<b>再下发</b>」。
     *
     * <table border="1">
     *   <caption>两个问题，两条规则，两种回溯策略</caption>
     *   <tr><th>问题</th><th>方法</th><th>回溯祖先链</th><th>依据</th></tr>
     *   <tr><td>我能不能<b>用</b> X</td><td>{@link #hasGrant}</td><td><b>否</b></td>
     *       <td>契约 §2.5 规则 4</td></tr>
     *   <tr><td>我能不能<b>再下发</b> X</td><td>本方法（经 {@code ResourceOwnerChecker.canRegrant}）</td>
     *       <td><b>是</b></td><td>契约 §2.5 规则 9</td></tr>
     * </table>
     *
     * <p><b>这两条看起来像冲突，不是冲突。</b> 规则 4 禁的是拿祖先链去判「能不能用」——
     * 那会让「上级有 = 下级自动有」，继承就从后门回来了。规则 9 的判据
     * <b>本身写的就是链</b>：「{@code target_node_id} 当前祖先链不再包含该资源
     * {@code owner_node_id} 或其有效授权链时，该行只读」。
     * <b>不看链就判不出链断没断</b>。
     *
     * <p>写这么长是因为下一个人读到「回溯祖先链」四个字的第一反应会是「这违反规则 4」，
     * 然后把它删掉 —— 而删掉之后<b>什么都不会报错</b>，只是调岗的教师又能把
     * 原校区的课授给新校区的学员了（契约 §2.5 规则 9 逐字描述的资产穿透）。
     *
     * @return {@code resourceId → 持有它的节点集合}；没有任何持有者的资源<b>键不出现</b>
     */
    public Map<Long, Set<Long>> grantHolders(ResourceType type, Collection<Long> resourceIds,
                                             Collection<Long> candidateNodeIds) {
        if (type == null || resourceIds == null || resourceIds.isEmpty()
                || candidateNodeIds == null || candidateNodeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Set<Long>> holders = new LinkedHashMap<>();
        for (ResourceGrantMapper.GrantHolderRow row : grantMapper.selectGrantHolders(
                type.code(), List.copyOf(resourceIds), List.copyOf(candidateNodeIds))) {
            holders.computeIfAbsent(row.getResourceId(), k -> new LinkedHashSet<>())
                    .add(row.getTargetNodeId());
        }
        return holders;
    }

    /**
     * 按资源统计「当前有效授权的目标节点数」（03-03 §1.1 的 {@code grantedNodeCount}）。
     *
     * <p>批量版本 —— 列表接口一页最多 100 行，逐行点查就是 100 次往返。
     *
     * @return 只含计数 &gt; 0 的资源；调用方对缺失键按 0 处理
     */
    public Map<Long, Integer> countActiveTargets(ResourceType type, List<Long> resourceIds) {
        if (type == null || resourceIds == null || resourceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return grantMapper.countActiveTargets(type.code(), resourceIds).stream()
                .collect(Collectors.toMap(ResourceGrantMapper.GrantCountRow::getResourceId,
                        ResourceGrantMapper.GrantCountRow::getTargetCount));
    }
}
