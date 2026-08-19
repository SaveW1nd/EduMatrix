package com.edumatrix.common.grant;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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
