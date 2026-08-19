package com.edumatrix.common.resource;

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
}
