package com.edumatrix.common.resource;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.subtree.NodeAncestorCache;
import com.edumatrix.common.subtree.NodePath;

/**
 * 受管资源的<b>归属与可用性</b>判定 —— 三类资源共用一处口径。
 *
 * <h2>三个方法，三个谓词，不可合并（E 定案 + F-81）</h2>
 * <table border="1">
 *   <caption>契约 §2.5 的两条规则各自需要的判定</caption>
 *   <tr><th>方法</th><th>含义</th><th>依据</th><th>谁用</th></tr>
 *   <tr><td>{@link #isOwner}</td><td><b>严格</b> {@code owner_node_id == nodeId}</td>
 *       <td>契约 §2.5 规则 8「写操作一律要求 {@code owner_node_id = 我的节点}，否则 403」</td>
 *       <td>模块 08 的编排权限（PRD F2-1 规则 8）</td></tr>
 *   <tr><td>{@link #canUse}</td><td>是 owner <b>∪</b> 被显式授权且在有效期内</td>
 *       <td>契约 §2.5 规则 1「授权人必须已拥有该资源（自己是 {@code owner_node_id}，
 *           或该资源已显式授权给自己所在节点且在有效期内）」</td>
 *       <td>模块 08 / 10 / 12~16 的资源<b>可见性与使用</b></td></tr>
 *   <tr><td>{@link #canRegrant}</td><td>是 owner <b>∪</b>（被授权且在有效期内
 *           <b>且授权链未跨管辖</b>）</td>
 *       <td>契约 §2.5 规则 1 <b>叠加</b>规则 9「跨管辖授权降级为只读：仅保留使用能力，
 *           <b>丧失再下发能力</b>」</td>
 *       <td>模块 11 的接口 37 / 38 / 40（<b>再下发</b>）</td></tr>
 * </table>
 *
 * <h2>为什么是三个而不是两个（F-81）</h2>
 * <p>E 定案那句「<b>一个布尔值无法同时正确</b>」没错，只是数错了 —— 要三个。
 * 三个动词，三个谓词：<b>写</b>用 {@link #isOwner}、<b>用</b>用 {@link #canUse}、
 * <b>再下发</b>用 {@link #canRegrant}。
 *
 * <p>在 {@link #canRegrant} 出现之前，契约 §2.5 规则 9 后半段
 *（「跨管辖授权丧失再下发能力」）<b>在代码里没有任何落地点</b>：
 * 教师 T 持有校区 A 的课程，调岗到校区 B 之后 {@code hasGrant} 仍为真、
 * {@code canUse} 仍为真，于是他<b>可以合法地把 A 的课授给 B 的新学员</b> ——
 * 只要促成一次调岗，A 的课程资产就进入 B 的分支并可无限复制。
 * 契约那一段逐字写的就是这个场景，而<b>它当时不会报错</b>。
 *
 * <p><b>{@code 04-实施计划.md} 模块 08 的「对外产出」原写「{@code isOwner} 与模块 11 的
 * 授权规则 1 共用」，那是不成立的</b>：规则 1 要的是并集、规则 8 要的是严格相等，
 * 一个布尔值无法同时正确。而模块 11 的对外产出把并集命名为 {@code GrantChecker.owns}，
 * 于是同一个「owner」在库里指了两件事 —— 调错不会报错。E 定案：收敛成本类的两个方法，
 * 模块 11 的 {@code GrantChecker.owns} 收敛到 {@link #canUse}（已改 04 §B 模块 11 对外产出）。
 *
 * <h2>未注册的类型抛异常，不返回 false</h2>
 * <p>三类受管资源（契约 §2.5 穷举）现已全部注册：{@link ResourceType#COURSE} 由模块 08、
 * {@link ResourceType#QUESTION} 由模块 10、{@link ResourceType#VIDEO} 由模块 09。
 * <b>但「未注册即响亮失败」这条纪律仍然生效</b>，因为它管的是<b>将来新增</b>的资源类型 ——
 * 返回 {@code false} 的表现是「授权引擎静默判定你不是 owner」，
 * 接口 200、字段齐全、结果错，正是本项目 1 号失败模式。
 */
@Component
public class ResourceOwnerChecker {

    private final Map<ResourceType, ResourceOwnerProvider> providers = new EnumMap<>(ResourceType.class);
    private final ResourceGrantReader grantReader;
    private final NodeAncestorCache ancestorCache;

    public ResourceOwnerChecker(List<ResourceOwnerProvider> registered,
                                ResourceGrantReader grantReader,
                                NodeAncestorCache ancestorCache) {
        this.grantReader = grantReader;
        this.ancestorCache = ancestorCache;
        for (ResourceOwnerProvider provider : registered) {
            ResourceOwnerProvider previous = providers.put(provider.resourceType(), provider);
            if (previous != null) {
                // 同一类型两个实现 = 两份同源实现。启动即失败，不留到运行期按注入顺序抽签
                throw new IllegalStateException("resourceType=" + provider.resourceType()
                        + " 注册了两个 ResourceOwnerProvider：" + previous.getClass().getName()
                        + " 与 " + provider.getClass().getName() + "。每类资源只能有一个归属真相源");
            }
        }
    }

    /** 已注册的资源类型（只读视图），供启动自检与测试钉住。 */
    public java.util.Set<ResourceType> registeredTypes() {
        return java.util.Collections.unmodifiableSet(providers.keySet());
    }

    /** 资源的归属节点；资源不存在 / 已删除 / 跨租户时 {@code null}。 */
    public Long ownerNodeIdOf(ResourceType type, Long resourceId) {
        return provider(type).ownerNodeIdOf(resourceId);
    }

    /**
     * <b>严格</b>归属判定：{@code owner_node_id == nodeId}。写操作（编排 / 编辑 / 删除 /
     * 上下架）用它，不通过一律 403（契约 §2.5 规则 8）。
     */
    public boolean isOwner(ResourceType type, Long resourceId, Long nodeId) {
        if (nodeId == null) {
            return false;
        }
        return Objects.equals(ownerNodeIdOf(type, resourceId), nodeId);
    }

    /**
     * <b>可用性</b>判定：是 owner ∪ 被显式授权且在有效期内（契约 §2.5 规则 1 / §2.4 查询语义）。
     *
     * <p>读操作与「能不能再下发」用它。<b>不回溯祖先链</b> —— 那是
     * {@link ResourceGrantReader} 保证的。
     */
    public boolean canUse(ResourceType type, Long resourceId, Long nodeId) {
        return isOwner(type, resourceId, nodeId) || grantReader.hasGrant(type, resourceId, nodeId);
    }

    /**
     * <b>再下发</b>判定：是 owner ∪（被显式授权且在有效期内 <b>且授权链未跨管辖</b>）。
     *
     * <p>契约 §2.5 规则 1（拥有）<b>叠加</b>规则 9（跨管辖降级只读）。
     * 模块 11 的接口 37（可授权清单）/ 38（授权）/ 40（改有效期）一律用它，
     * <b>不用 {@link #canUse}</b>。
     *
     * <h2>「授权链未跨管辖」怎么判</h2>
     * <p>契约 §2.5 规则 9 的判据逐字：「授权行的 {@code target_node_id} 当前祖先链
     * <b>不再包含</b>该资源 {@code owner_node_id} <b>或其有效授权链</b>时，该行只读」。
     * 落地成自根向下走一遍祖先链：
     * <pre>
     *   intact = false
     *   for a in 祖先链(根 → 我的父节点):
     *       intact = (a == owner) || (intact &amp;&amp; a 当前有效持有该资源)
     * </pre>
     * <p>即：<b>从 owner 那一层起，往下每一层都必须显式持有</b>，直到我的父节点为止。
     * 这正是契约 §2.5 规则 3「不向下继承，每一层都必须显式授权」的链形态。
     *
     * <h2>「有效授权链」取<b>链</b>的读法，不取「链上存在任意一个持有者」（F-81 登记）</h2>
     * <p>两种读法在一处分叉：下级管理员 M 被降级为只读之后，<b>M 的下级</b>算不算？
     * <ul>
     *   <li>「存在任意持有者」读法：M 仍持有 → M 的教师判为链完整 → <b>照样能再下发</b>；
     *   <li>本实现（链读法）：M 自己已跨管辖 → 从 owner 到教师的链在 M 这一层断了 → 只读。
     * </ul>
     * <p>取后者，因为规则 9 存在的<b>唯一理由</b>就是堵资产穿透，而前者只堵住了第一层：
     * 把整个下级管理员分支连人带资源调岗过去，穿透照旧发生。
     *
     * <p><b>代价照实写</b>：真悬挂行（父级已被撤销、子级仍持有，契约 §2.5 规则 6）
     * 同样会被判为不可再下发。这是<b>对的</b>——PRD FR-2 规则 1 逐字：那种行「学生仍能学
     * 但导师无法在自己的资源库中管理该课程」，本来就该只读。跨级直授（上级越过中间层
     * 直接授给孙节点）产生的行同理，PRD 也把它归为悬挂授权。
     */
    public boolean canRegrant(ResourceType type, Long resourceId, Long nodeId) {
        if (nodeId == null || resourceId == null) {
            return false;
        }
        return regrantableIds(type, List.of(resourceId), nodeId).contains(resourceId);
    }

    /**
     * {@link #canRegrant} 的<b>批量</b>版 —— 接口 37 过滤清单、接口 38 校验 500 个资源都用它。
     *
     * <p>无论传进来多少资源，<b>固定三次查询</b>：一次祖先链（走 {@code node:anc:} 缓存）、
     * 一次批量归属、一次批量持有者。逐个调 {@link #canRegrant} 是 3N 次 ——
     * 慢，但<b>不报错</b>，正是这类代码最容易留下的东西。
     *
     * @return {@code candidateIds} 中<b>可再下发</b>的那些，保持传入顺序；空集合法
     */
    public Set<Long> regrantableIds(ResourceType type, Collection<Long> candidateIds, Long nodeId) {
        if (type == null || nodeId == null || candidateIds == null || candidateIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = List.copyOf(new LinkedHashSet<>(candidateIds));

        // 祖先链：根 → 我的父节点。哨兵 0 已被 parseAncestorIds 跳过
        String ancestors = ancestorCache.get(nodeId);
        List<Long> chain = ancestors == null ? List.of() : NodePath.parseAncestorIds(ancestors);

        Map<Long, Long> owners = provider(type).ownerNodeIdsOf(ids);
        Map<Long, Set<Long>> holders = chain.isEmpty()
                ? Map.of() : grantReader.grantHolders(type, ids, chain);

        Set<Long> regrantable = new LinkedHashSet<>();
        for (Long resourceId : ids) {
            Long ownerNodeId = owners.get(resourceId);
            if (ownerNodeId == null) {
                // 资源不存在 / 已删除 / 跨租户 —— 与「你无权」同一个出口（契约 §2.5 规则 1 防探测）
                continue;
            }
            if (nodeId.equals(ownerNodeId)) {
                regrantable.add(resourceId);            // 自有：永久可授出（03-02 §9.1）
                continue;
            }
            if (!grantReader.hasGrant(type, resourceId, nodeId)) {
                continue;                               // 我自己都没有，谈不上再下发
            }
            if (chainIntact(chain, ownerNodeId, holders.getOrDefault(resourceId, Set.of()))) {
                regrantable.add(resourceId);
            }
            // 否则：跨管辖 —— 能用，不能再下发（契约 §2.5 规则 9）
        }
        return regrantable;
    }

    /**
     * 自根向下走一遍祖先链：从 {@code ownerNodeId} 那一层起，往下每一层都必须持有。
     *
     * <p><b>顺序不能反</b>：{@code ancestors} 列是「根在前」的逗号串（契约 §2.3 结构约束 3，
     * 形如 {@code 0,100,101,205}），{@code NodePath.parseAncestorIds} 保持这个顺序。
     * 从叶往根走会把「owner 在我上面第 3 层、第 2 层断了」判成链完整。
     */
    private static boolean chainIntact(List<Long> chain, Long ownerNodeId, Set<Long> holders) {
        boolean intact = false;
        for (Long ancestor : chain) {
            intact = ancestor.equals(ownerNodeId) || (intact && holders.contains(ancestor));
        }
        return intact;
    }

    private ResourceOwnerProvider provider(ResourceType type) {
        ResourceOwnerProvider provider = providers.get(type);
        if (provider == null) {
            // 三类受管资源（COURSE / QUESTION / VIDEO，契约 §2.5 穷举）现已全部注册，
            // 正常路径走不到这里。留着它是因为「未注册即响亮失败」这条纪律对
            // 【将来新增的资源类型】同样成立 —— 而那时返回 false 仍然是最坏的选项
            throw new IllegalStateException("resourceType=" + type + " 尚未注册 ResourceOwnerProvider。"
                    + "每类受管资源必须由其所属领域注册一个提供方；此处【响亮失败】，"
                    + "而不是返回 false 制造一次静默的越权判定（接口 200、字段齐全、结果错）");
        }
        return provider;
    }
}
