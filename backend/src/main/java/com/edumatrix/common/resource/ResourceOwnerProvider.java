package com.edumatrix.common.resource;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「某个受管资源的 {@code owner_node_id} 是谁」—— 每个领域各注册一个实现。
 *
 * <p>接口在 {@code common/}、实现在各自领域内（模块 08 课程、模块 09 视频、模块 10 题目），
 * 由 {@link ResourceOwnerChecker} 按 {@link ResourceType} 分发。
 * 这是为了让 {@code org} 领域的授权引擎（模块 11）能问三类资源的归属，
 * 而不必 import 另外三个领域 —— 检查③ 拦的是 import。
 *
 * <p><b>实现方只回答「归属是谁」，不做任何权限判定</b>。判定在
 * {@link ResourceOwnerChecker}，那样三类资源的口径必然一致。
 */
public interface ResourceOwnerProvider {

    /** 本实现负责哪一类资源。同一类型注册两个实现会在启动时失败（见注册表）。 */
    ResourceType resourceType();

    /**
     * 资源的归属节点。
     *
     * @param resourceId 资源 ID
     * @return {@code owner_node_id}；资源不存在、已逻辑删除、或被租户插件过滤掉时返回 {@code null}
     */
    Long ownerNodeIdOf(Long resourceId);

    /**
     * 批量版：一次问一批资源的归属节点（模块 11 新增）。
     *
     * <h2>为什么加在这里，而不是新开一个 SPI</h2>
     * <p>它问的是<b>同一个问题</b> —— 「归属是谁」，只是一次问一批。本接口的契约
     *（「只回答归属是谁，不做任何权限判定」）一个字都没变，所以它属于这里。
     * 与之相对，「我可授权的资源分页 + 资源名」是<b>另一个</b>问题，
     * 那个开在 {@link GrantableResourceProvider}（F-89 定案）。
     *
     * <h2>为什么必须有批量版</h2>
     * <p>03-02 §9.2 的 {@code resourceIds} 单次最多 <b>500</b> 个，而模块 11 对每一个
     * 都要判「我是不是 owner」（契约 §2.5 规则 1）。逐个点查就是 500 次往返 ——
     * 一次授权可以慢到秒级，而<b>接口仍然返回 200</b>。
     *
     * <p><b>默认实现是逐个调 {@link #ownerNodeIdOf}</b>，语义上永远正确；
     * 各领域<b>应当</b>覆写成一条 {@code selectBatchIds}。默认实现存在的意义是：
     * 将来新增第四类资源时，忘了覆写只会慢，不会错。
     *
     * @param resourceIds 资源 ID 集合；{@code null} 或空集返回空 Map
     * @return 只含<b>查得到</b>的资源；不存在 / 已删除 / 跨租户的键<b>不出现</b>，
     *         调用方对缺失键必须按「不是我的」处理，而不是按 {@code null} 通配
     */
    default Map<Long, Long> ownerNodeIdsOf(Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> owners = new LinkedHashMap<>();
        for (Long resourceId : resourceIds) {
            Long ownerNodeId = ownerNodeIdOf(resourceId);
            if (ownerNodeId != null) {
                owners.put(resourceId, ownerNodeId);
            }
        }
        return owners;
    }
}
