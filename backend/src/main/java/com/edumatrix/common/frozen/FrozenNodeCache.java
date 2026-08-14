package com.edumatrix.common.frozen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.edumatrix.common.frozen.mapper.FrozenNodeMapper;
import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.mapper.OrgNodeSubtreeMapper;

/**
 * 冻结集：{@code frozen:{tenantId}} → SET，存该租户当前<b>被停用的节点 id</b>（契约 §2.3 定案）。
 *
 * <h2>为什么是「冻结集」而不是「用户集」</h2>
 * <p>停用一个管理员要让他子树里 1.1 万人立刻失去访问权。级联写库与主动踢 Token 都按
 * <b>受影响的人数</b>（1.1 万）付代价，中途失败还会留下半冻结状态；而冻结集的规模只由
 * <b>被停用的节点数</b>决定（一个租户通常个位数）。同一个业务效果，代价差四个数量级。
 * 契约 §2.3 的方案对照表据此否掉了另外两条路 —— <b>不要为「让停用立刻生效」去遍历子树
 * 逐个 {@code StpUtil.logout()}</b>，那就是被否掉的那一条。
 *
 * <h2>两条顺序约束，方向都朝「宁可多拦一瞬」</h2>
 * <pre>
 * 停用：先 SADD 冻结集  →  再提交 org_node.status = 1 的事务
 * 启用：先提交事务      →  再 SREM
 * </pre>
 * <p>反过来会出现「库里已停用但 Redis 还没写」的放行窗口。因此 {@link #add} 与
 * {@link #remove} <b>遇 Redis 故障一律抛异常</b>，不吞：停用时抛出 → 事务回滚 →
 * 库与冻结集一致地「都没停」；若吞掉，就成了「库停了、冻结集没记」，正是要避免的那个窗口。
 *
 * <h2>求交规则：契约那一节缺了 {@code node_type}，这里补齐</h2>
 * <p>契约 §2.3 的每请求规则原文是「拆祖先链 → 与 {@code frozen:{tenantId}} 求交集 →
 * 命中即拒」，<b>不分节点类型</b>；而同一节的登录侧两段 SQL，②段明确带
 * {@code AND node_type = 1}，并专门解释「只有管理员节点的停用会冻结子树，
 * 教师节点的 {@code status=1} 只通过①段停掉他自己」。
 *
 * <p>照冻结集那段的字面实现，后果是：<b>停用教师 T → 学员 S 的祖先链含 T → 求交命中 →
 * S 被拒</b> —— 正是契约自己称为「业务事故」的那件事，也直接违反 PRD F1-2 验收标准。
 * 表现形态还格外隐蔽：登录侧是对的，所以 S 被踢出去后<b>重新登录能进来，进来一发请求又被踢</b>，
 * 两边都「按各自的规则执行」，日志里没有任何报错。
 *
 * <p><b>定案（本类实现）</b>：冻结集当作<b>快速否定过滤器</b> ——
 * <ol>
 *   <li><b>未命中 → 直接放行</b>，零查库。这是绝大多数请求走的路，热路径开销不变；
 *   <li><b>命中 → 套用与登录<b>完全相同</b>的两段规则</b>：自身命中即拒；
 *       祖先命中且该祖先 {@code node_type = 1} 才拒。
 * </ol>
 * <b>{@code status} 仍以冻结集为准、绝不查库</b> —— 只查 {@code node_type}。
 * 这保住了「先 SADD 再提交」的抢跑保护：那一瞬间库里还是 {@code status = 0}，
 * 若回头查 status 就等于把刚建立的保护又拆掉。
 *
 * <p>{@code node_type} 是<b>不变量</b>：节点建出来是什么类型就永远是什么类型，全系统没有任何
 * 接口能改它（契约 §2.3 的结构约束里，改的是 {@code parent_id} 与 {@code ancestors}，
 * 从来不是 {@code node_type}）。所以那次点查可以无限期记忆化 —— 于是「每次命中查一次库」
 * 实际是「<b>每个被停用的节点查一次库</b>」，而被停用的节点数是个位数。
 *
 * <h2>Redis 不可用时降级查库，不是跳过</h2>
 * <p>契约 §2.3：「这是安全校验，不是缓存加速；『缓存挂了就放行』等于停用功能整体失效」。
 * {@link #isFrozen} 捕获 Redis 异常后走 {@link FrozenNodeMapper#selectFirstDisabled}
 * （拆 {@code ancestors} 主键 {@code IN}，约 5 行点查），<b>判定结果与走 Redis 时一致</b>。
 *
 * <h2>谁调用它</h2>
 * <ul>
 *   <li><b>写侧</b>：模块 06 停用/启用节点时调 {@link #add} / {@link #remove}；
 *   <li><b>读侧</b>：模块 02 的每请求鉴权与登录校验调 {@link #isFrozen}。
 * </ul>
 * <p><b>为什么放在 {@code common/} 而不是 {@code auth/}</b>：它是「org 写、auth 读」的
 * 跨领域基础设施，与 {@code common/subtree/NodeAncestorCache}（同样是组织状态的 Redis 缓存、
 * 被多个领域消费）完全同构。放进 {@code auth/} 的话，模块 06 就必须
 * {@code import com.edumatrix.auth.*} —— 那会撞上 05-工程结构.md §A1 的第三条硬约束
 * 「领域包不得互相 import」。
 *
 * <p><b>为什么用 {@code @Component} 而不是往 {@code CommonBeanConfig} 加一个 {@code @Bean}</b>：
 * 后者要改模块 01 已交付的文件。本类是<b>新增</b>的公共层构件，自带装配即可让模块 01
 * 一个字不动 —— 「不重写公共层」是纪律，「不能往 common 加新类」不是。
 */
@Component
public class FrozenNodeCache {

    private static final Logger log = LoggerFactory.getLogger(FrozenNodeCache.class);

    private final StringRedisTemplate redisTemplate;
    private final FrozenNodeMapper frozenNodeMapper;
    private final OrgNodeSubtreeMapper nodeMapper;

    /**
     * {@code nodeId → node_type} 的记忆化。
     *
     * <p>只装<b>被停用过的节点</b>（求交命中才会查），一个租户个位数，全平台也不会成为内存问题，
     * 因此不设淘汰。{@code node_type} 是不变量，缓存永不失效是正确的 ——
     * 若将来真出现「改节点类型」的接口，那才是要回来改这里的信号。
     */
    private final Map<Long, Integer> nodeTypeMemo = new ConcurrentHashMap<>();

    public FrozenNodeCache(StringRedisTemplate redisTemplate,
                           FrozenNodeMapper frozenNodeMapper,
                           OrgNodeSubtreeMapper nodeMapper) {
        this.redisTemplate = redisTemplate;
        this.frozenNodeMapper = frozenNodeMapper;
        this.nodeMapper = nodeMapper;
    }

    // =====================================================================
    // 写侧（模块 06）
    // =====================================================================

    /**
     * 把节点加入冻结集。<b>必须在 {@code org_node.status = 1} 的事务提交之前调用</b>（契约 §2.3）。
     *
     * <p>Redis 故障时<b>直接抛出</b>，让停用事务回滚 —— 见类注释「两条顺序约束」。
     */
    public void add(Long tenantId, Long nodeId) {
        requireArgs(tenantId, nodeId);
        redisTemplate.opsForSet().add(RedisKeys.frozenSet(tenantId), String.valueOf(nodeId));
    }

    /**
     * 把节点移出冻结集。<b>必须在启用事务提交之后调用</b>（契约 §2.3）。
     *
     * <p>同样不吞异常：吞掉的后果是「库里已启用、冻结集里还留着」，该账号<b>永远登不进来</b>
     * 且没有任何接口能修复它 —— 与契约 §2.3 拆掉 {@code sys_user.status} 侧路时
     * 要避免的「停用可逆、启用不可逆」是同一类错误。
     */
    public void remove(Long tenantId, Long nodeId) {
        requireArgs(tenantId, nodeId);
        redisTemplate.opsForSet().remove(RedisKeys.frozenSet(tenantId), String.valueOf(nodeId));
    }

    // =====================================================================
    // 读侧（模块 02：登录校验 + 每请求鉴权）
    // =====================================================================

    /**
     * 本人所在节点或其上级是否被停用（契约 §2.3 两段规则）。命中即应返回 {@code 10017}。
     *
     * @param tenantId    当前租户；{@code null} 时（平台超管）直接放行 —— 超管不属于任何租户，
     *                    没有哪个机构管理员能冻结他
     * @param nodeId      本人所在节点（{@code sys_user.node_id}）
     * @param ancestorIds 祖先节点 id，<b>由 {@link NodePath#parseAncestorIds} 现取现拆</b>，
     *                    绝不来自 Token（契约 §2.3：{@code ancestors} 写进 Token 就固化成
     *                    发证时刻的快照，任何一次节点移动都要等 Token 过期才生效）
     */
    public boolean isFrozen(Long tenantId, Long nodeId, List<Long> ancestorIds) {
        if (tenantId == null || nodeId == null) {
            return false;
        }
        List<Long> ancestors = ancestorIds == null ? Collections.emptyList() : ancestorIds;

        List<Long> hits;
        try {
            hits = intersectFrozenSet(tenantId, nodeId, ancestors);
        } catch (RuntimeException e) {
            // 契约 §2.3：Redis 不可用时降级查库，不得跳过校验
            log.warn("冻结集不可用，降级查库判定 tenantId={} nodeId={}", tenantId, nodeId, e);
            return frozenNodeMapper.selectFirstDisabled(nodeId, ancestors) != null;
        }

        if (hits.isEmpty()) {
            // 绝大多数请求走这里：一次 Redis 判集合，零查库
            return false;
        }
        return hitMeansFrozen(nodeId, hits);
    }

    /**
     * 与冻结集求交：自身 + 祖先链。
     *
     * <p>用一次 {@code SMISMEMBER}（Redis 6.2+）批量判，而不是 N 次往返 ——
     * 契约 §2.3 写的「SISMEMBER × 6」是<b>规模</b>的说明（约 6 个成员），不是要求发 6 个命令。
     */
    private List<Long> intersectFrozenSet(Long tenantId, Long nodeId, List<Long> ancestors) {
        List<Long> candidates = new ArrayList<>(ancestors.size() + 1);
        candidates.add(nodeId);
        for (Long ancestor : ancestors) {
            if (ancestor != null && !ancestor.equals(nodeId)) {
                candidates.add(ancestor);
            }
        }
        Object[] members = new Object[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            members[i] = String.valueOf(candidates.get(i));
        }
        // SMISMEMBER 的返回是「成员 → 是否命中」的映射
        Map<Object, Boolean> flags = redisTemplate.opsForSet()
                .isMember(RedisKeys.frozenSet(tenantId), members);
        if (flags == null) {
            throw new IllegalStateException("冻结集判定返回 null，按 Redis 不可用处理");
        }
        List<Long> hits = new ArrayList<>(2);
        for (Long candidate : candidates) {
            if (Boolean.TRUE.equals(flags.get(String.valueOf(candidate)))) {
                hits.add(candidate);
            }
        }
        return hits;
    }

    /**
     * 命中之后的判定：<b>与登录侧两段规则逐字相同</b>。
     *
     * <p>①自身命中 → 拒（教师/学生的「仅本人」停用由此生效）；
     * ②祖先命中且 {@code node_type = 1} → 拒（管理员的分支冻结由此生效）。
     * 祖先是教师时不拒 —— 教师停用不级联，其名下学员照常学习。
     */
    private boolean hitMeansFrozen(Long nodeId, List<Long> hits) {
        for (Long hit : hits) {
            if (hit.equals(nodeId)) {
                return true;
            }
        }
        for (Long hit : hits) {
            Integer nodeType = nodeTypeOf(hit);
            if (nodeType != null && nodeType == NodePath.NODE_TYPE_ADMIN) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取节点类型，无限期记忆化（{@code node_type} 是不变量，见类注释）。
     *
     * <p>查不到（节点已被删除）时返回 {@code null} 且<b>不记忆</b> ——
     * 记住一个 null 会让「删了又以同 id 重建」这种情况永远判错；而它查不到本身就是
     * 冻结集里留了脏成员的信号，不该被缓存掩盖。
     */
    private Integer nodeTypeOf(Long nodeId) {
        Integer memoized = nodeTypeMemo.get(nodeId);
        if (memoized != null) {
            return memoized;
        }
        NodePath path = nodeMapper.selectPath(nodeId);
        if (path == null || path.getNodeType() == null) {
            log.warn("冻结集命中的节点在库中不存在或无 node_type，按不冻结处理 nodeId={}", nodeId);
            return null;
        }
        nodeTypeMemo.put(nodeId, path.getNodeType());
        return path.getNodeType();
    }

    private static void requireArgs(Long tenantId, Long nodeId) {
        if (tenantId == null || nodeId == null) {
            throw new IllegalArgumentException(
                    "冻结集读写必须同时给出 tenantId 与 nodeId（契约 §2.3：冻结集按租户分片）");
        }
    }
}
