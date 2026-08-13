package com.edumatrix.common.subtree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.mapper.OrgNodeSubtreeMapper;
import com.edumatrix.common.tenant.CurrentContextProvider;

/**
 * 数据权限的唯一入口 —— <b>全系统只有一条规则：你能看到的数据 = 你所在节点的子树</b>（契约 §2.4）。
 *
 * <p><b>所有模块一律调用本类，不得自写子树查询。</b>各模块自己写的后果是：契约 §2.4 的三条
 * 执行路径变成 N 种写法，且极易退化成 {@code FIND_IN_SET} —— 而契约 §7.1 写死了
 * <b>「{@code FIND_IN_SET} 出现在慢查询日志中即视为缺陷」</b>。
 *
 * <h2>为什么这个包不叫 {@code datascope}</h2>
 * <p>{@code DataScope} 是<b>已被契约否决的概念</b>（{@code sys_role} 早已删掉 {@code data_scope} 列，
 * 02-数据库设计 §3.2 三条边界之二：「角色不得扩大可见范围」）。用它当包名会把后来者往
 * 「数据权限分档」上引，而本系统压根没有第二套过滤逻辑。
 *
 * <h2>FIND_IN_SET 是语义定义，不是执行写法</h2>
 * <p>契约 §2.4 用 {@code node.id = #{myNodeId} OR FIND_IN_SET(#{myNodeId}, node.ancestors)}
 * 描述"子树"这个概念。它是<b>作用在列上的函数，无法走索引</b>，直接内联会让
 * <b>每次带数据权限的查询都全表扫描 {@code org_node}</b> —— 而这是全系统执行频率最高的条件。
 * 实现必须按选路表分三条路径：
 *
 * <table border="1">
 *   <caption>契约 §2.4 选路表</caption>
 *   <tr><th>角色 / 场景</th><th>执行写法</th><th>命中索引</th></tr>
 *   <tr><td><b>教师（最高频）</b></td>
 *       <td>子树 ≡ 直接子节点，退化为 {@code parent_id = ? AND node_type = 3}</td>
 *       <td>{@code idx_parent_type}</td></tr>
 *   <tr><td><b>管理员：取整棵子树</b></td>
 *       <td>前缀 LIKE 解析出子树 ID 集合，再对业务表 {@code node_id IN (...)}</td>
 *       <td>{@code idx_ancestors(255)}</td></tr>
 *   <tr><td><b>管理员：逐层浏览</b></td>
 *       <td>按 {@code parent_id} 逐层展开（树懒加载、面包屑）</td>
 *       <td>{@code idx_tenant_parent_sort}</td></tr>
 * </table>
 *
 * <h2>越界返回什么（三分法，全系统统一）</h2>
 * <ul>
 *   <li>{@link #assertInSubtree} —— 「我要操作的东西」越界（路径上的 {@code /xxx/{id}}）→ <b>404</b>；
 *   <li>{@link #assertTargetInSubtree} —— 「我选的目标」越界（请求体里的 {@code targetParentId}、
 *       {@code studentIds}）→ <b>10107</b>；
 *   <li>功能权限不足 → 403，那由 {@code @SaCheckPermission} 负责，不经过本类。
 * </ul>
 */
public class SubtreeScopeHelper {

    private static final Logger log = LoggerFactory.getLogger(SubtreeScopeHelper.class);

    private final OrgNodeSubtreeMapper nodeMapper;
    private final NodeAncestorCache ancestorCache;
    private final CurrentContextProvider contextProvider;

    public SubtreeScopeHelper(OrgNodeSubtreeMapper nodeMapper,
                              NodeAncestorCache ancestorCache,
                              CurrentContextProvider contextProvider) {
        this.nodeMapper = nodeMapper;
        this.ancestorCache = ancestorCache;
        this.contextProvider = contextProvider;
    }

    // ======================================================================
    // 取子树
    // ======================================================================

    /**
     * 取「我」的子树节点 ID 集合（<b>含自身</b>），按契约 §2.4 选路表自动选路。
     *
     * <p>结果用于业务表的 {@code WHERE node_id IN (...)}。
     *
     * <p><b>永远不会返回空集</b>：至少含 {@code myNodeId} 自己。若某次调用得到空集，
     * 说明该节点在 {@code org_node} 里查不到（跨租户或已删除），此时按契约 §7.1
     * 日志分级记 ERROR —— <b>「数据权限过滤条件为空集时 ERROR，那意味着过滤逻辑写漏了，
     * 正在返回全量数据」</b>。调用方拿到空集必须当作"什么都看不到"，绝不可当作"不加过滤"。
     */
    public List<Long> subtreeNodeIds(Long myNodeId) {
        if (myNodeId == null) {
            log.error("数据权限过滤：myNodeId 为空。调用方必须先确定"
                    + "「我在树上的位置」，返回空集而非放行（契约 §7.1 日志分级）");
            return Collections.emptyList();
        }
        NodePath me = nodeMapper.selectPath(myNodeId);
        if (me == null) {
            log.error("数据权限过滤条件为空集：节点 {} 不存在或不可见（跨租户/已删除）。"
                    + "调用方必须当作「什么都看不到」处理，绝不可退化为不加过滤", myNodeId);
            return Collections.emptyList();
        }

        List<Long> ids = new ArrayList<>();
        ids.add(me.getId());

        Integer type = me.getNodeType();
        if (type != null && type == NodePath.NODE_TYPE_STUDENT) {
            // 学生是叶子，子树即自身
            return ids;
        }
        if (type != null && type == NodePath.NODE_TYPE_TEACHER) {
            // 最高频路径：教师节点下只能挂学生，子树 ≡ 直接子节点，走 idx_parent_type
            ids.addAll(nodeMapper.selectChildIdsByType(me.getId(), NodePath.NODE_TYPE_STUDENT));
            return ids;
        }
        // 平台超管 / 管理员：前缀 LIKE 取整棵子树，走 idx_ancestors
        ids.addAll(nodeMapper.selectSubtreeIdsByPrefix(me.selfPrefix()));
        return ids;
    }

    /** 取当前登录人的子树节点 ID 集合。会话由模块 02 提供（见 {@link CurrentContextProvider}）。 */
    public List<Long> subtreeNodeIds() {
        return subtreeNodeIds(currentNodeId());
    }

    /**
     * 路径③：按 {@code parent_id} 逐层展开（树懒加载、面包屑）。
     *
     * <p>浏览组织树时用它，<b>不要用 {@link #subtreeNodeIds}</b> ——
     * 展开一层只需要一层，取整棵子树是白付代价。
     */
    public List<Long> childNodeIds(Long parentNodeId) {
        return parentNodeId == null ? Collections.emptyList() : nodeMapper.selectChildIds(parentNodeId);
    }

    // ======================================================================
    // 判定「目标是否在我的子树内」
    // ======================================================================

    /**
     * 目标节点是否在「我」的子树内（含目标 == 我）。
     *
     * <p><b>实现走「读目标的祖先链」而不是「取我的整棵子树再 contains」</b>：
     * 前者是一次点查（还能命中 {@code node:anc:} 缓存），后者在机构根节点上要拉出上万个 ID。
     * 判定式：{@code target.id == myNodeId || myNodeId ∈ target.ancestors}，
     * 与契约 §2.4 的语义定义等价。
     */
    public boolean isInSubtree(Long myNodeId, Long targetNodeId) {
        if (myNodeId == null || targetNodeId == null) {
            return false;
        }
        if (myNodeId.equals(targetNodeId)) {
            return true;
        }
        String ancestors = ancestorCache.get(targetNodeId);
        if (ancestors == null) {
            // 目标不存在，或被租户插件过滤掉（跨租户）
            return false;
        }
        // parseAncestorIds 已跳过首位哨兵 0。这不影响判定：
        // 谁的 myNodeId 都不会是平台根哨兵——超管的 node_id 就是 0，
        // 而超管走的是「租户插件整体放行」那条通道，不靠这里
        return NodePath.parseAncestorIds(ancestors).contains(myNodeId);
    }

    /** 目标是否在当前登录人的子树内。 */
    public boolean isInSubtree(Long targetNodeId) {
        return isInSubtree(currentNodeId(), targetNodeId);
    }

    /**
     * 断言「<b>我要操作的东西</b>」在我的子树内，否则 <b>404</b>。
     *
     * <p>用于路径参数上的资源：{@code GET/PUT/DELETE /xxx/{id}}。
     * 返回 404 而不是 403，是为了<b>不暴露存在性</b> —— 与跨租户的处置一致。
     */
    public void assertInSubtree(Long targetNodeId) {
        assertInSubtree(currentNodeId(), targetNodeId);
    }

    /** 同上，显式传「我」的节点（Job 等无会话场景）。 */
    public void assertInSubtree(Long myNodeId, Long targetNodeId) {
        if (!isInSubtree(myNodeId, targetNodeId)) {
            throw BizException.notFound(targetNodeId);
        }
    }

    /**
     * 断言「<b>我选的目标</b>」在我的子树内，否则 <b>10107</b>（HTTP 200）。
     *
     * <p>用于请求体/查询参数里显式指定的目标：{@code targetParentId}、{@code targetNodeIds}、
     * {@code studentIds}、{@code nodeId}。返回业务码而不是 404，是因为
     * <b>用户主动选了越界对象，需要明确提示"请重新选择"，而不是静默的"数据不存在"</b>。
     */
    public void assertTargetInSubtree(Long targetNodeId) {
        assertTargetInSubtree(currentNodeId(), targetNodeId);
    }

    /** 同上，显式传「我」的节点。 */
    public void assertTargetInSubtree(Long myNodeId, Long targetNodeId) {
        if (!isInSubtree(myNodeId, targetNodeId)) {
            throw BizException.targetOutOfScope(targetNodeId, null);
        }
    }

    /**
     * 批量断言「我选的目标」全部在子树内，越界的一并列出。
     *
     * <p><b>整批拒绝，不做部分成功</b>：发布作业（{@code 30011}）、批量授权、批量打标签
     * 都要求整批回滚并在响应里列出越界对象。部分成功会让调用方无从判断到底哪些生效了。
     */
    public void assertTargetsInSubtree(Long myNodeId, List<Long> targetNodeIds) {
        if (targetNodeIds == null || targetNodeIds.isEmpty()) {
            return;
        }
        List<Long> invalid = new ArrayList<>();
        for (Long targetNodeId : targetNodeIds) {
            if (!isInSubtree(myNodeId, targetNodeId)) {
                invalid.add(targetNodeId);
            }
        }
        if (!invalid.isEmpty()) {
            throw BizException.targetOutOfScope(invalid, invalid);
        }
    }

    private Long currentNodeId() {
        return contextProvider.getNodeId();
    }
}
