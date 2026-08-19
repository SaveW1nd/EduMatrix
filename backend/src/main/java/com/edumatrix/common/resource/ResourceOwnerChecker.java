package com.edumatrix.common.resource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.edumatrix.common.grant.ResourceGrantReader;

/**
 * 受管资源的<b>归属与可用性</b>判定 —— 三类资源共用一处口径。
 *
 * <h2>两个方法，两个谓词，不可合并（E 定案）</h2>
 * <table border="1">
 *   <caption>契约 §2.5 的两条规则各自需要的判定</caption>
 *   <tr><th>方法</th><th>含义</th><th>依据</th><th>谁用</th></tr>
 *   <tr><td>{@link #isOwner}</td><td><b>严格</b> {@code owner_node_id == nodeId}</td>
 *       <td>契约 §2.5 规则 8「写操作一律要求 {@code owner_node_id = 我的节点}，否则 403」</td>
 *       <td>模块 08 的编排权限（PRD F2-1 规则 8）</td></tr>
 *   <tr><td>{@link #canUse}</td><td>是 owner <b>∪</b> 被显式授权且在有效期内</td>
 *       <td>契约 §2.5 规则 1「授权人必须已拥有该资源（自己是 {@code owner_node_id}，
 *           或该资源已显式授权给自己所在节点且在有效期内）」</td>
 *       <td>模块 11 的授权前提、模块 08 的资源可见性</td></tr>
 * </table>
 *
 * <p><b>{@code 04-实施计划.md} 模块 08 的「对外产出」原写「{@code isOwner} 与模块 11 的
 * 授权规则 1 共用」，那是不成立的</b>：规则 1 要的是并集、规则 8 要的是严格相等，
 * 一个布尔值无法同时正确。而模块 11 的对外产出把并集命名为 {@code GrantChecker.owns}，
 * 于是同一个「owner」在库里指了两件事 —— 调错不会报错。E 定案：收敛成本类的两个方法，
 * 模块 11 的 {@code GrantChecker.owns} 收敛到 {@link #canUse}（已改 04 §B 模块 11 对外产出）。
 *
 * <h2>未注册的类型抛异常，不返回 false</h2>
 * <p>模块 08 只注册 {@link ResourceType#COURSE}；{@code VIDEO} 由模块 09、
 * {@code QUESTION} 由模块 10 各补一个 {@link ResourceOwnerProvider}。
 * 在那之前问它们的归属<b>必须响亮失败</b> —— 返回 {@code false} 的表现是
 * 「授权引擎静默判定你不是 owner」，接口 200、字段齐全、结果错，
 * 正是本项目 1 号失败模式。
 */
@Component
public class ResourceOwnerChecker {

    private final Map<ResourceType, ResourceOwnerProvider> providers = new EnumMap<>(ResourceType.class);
    private final ResourceGrantReader grantReader;

    public ResourceOwnerChecker(List<ResourceOwnerProvider> registered, ResourceGrantReader grantReader) {
        this.grantReader = grantReader;
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

    private ResourceOwnerProvider provider(ResourceType type) {
        ResourceOwnerProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalStateException("resourceType=" + type + " 尚未注册 ResourceOwnerProvider —— "
                    + "视频由模块 09、题目由模块 10 各补一个（见 04-实施计划.md 对应模块的"
                    + "「做完什么算做完」）。此处响亮失败，而不是返回 false 制造一次静默的越权判定");
        }
        return provider;
    }
}
