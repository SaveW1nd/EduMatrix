package com.edumatrix.common.subtree;

import java.util.Collection;
import java.util.Map;

/**
 * 批量取节点名 —— 接口在 {@code common/}，实现在 {@code org/node/}。
 *
 * <p>03-03 §1.1 / §1.2 的 {@code ownerNodeName} 要它，而 {@code course} 领域
 * 不能 import {@code org}（约定检查③）。与 {@link CurrentNodeProvider} 同型。
 *
 * <p><b>只读名字，不承载任何判定</b>：「这个节点在不在我子树内」是
 * {@link SubtreeScopeHelper} 的事。取名字的调用方必须已完成自己的鉴权。
 */
public interface NodeNameReader {

    /**
     * @return {@code nodeId → node_name}；查不到的 id（不存在 / 已删除 / 跨租户）直接缺席
     */
    Map<Long, String> nodeNames(Collection<Long> nodeIds);
}
