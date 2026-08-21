package com.edumatrix.vod.media.service;

import org.springframework.stereotype.Component;

import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.common.subtree.OrgRootGuard;
import com.edumatrix.vod.media.entity.VodVideo;
import com.edumatrix.vod.media.mapper.VodVideoMapper;

/**
 * 媒资的取行 + 三分法判定 —— <b>五个接口的 404 / 403 / 20015 分界只在这里做一次</b>。
 *
 * <h2>路径上的媒资：「不存在」与「不可见」统一 404（F-49）</h2>
 * <p>03-03 §7.4 / §7.5 / §7.6 的「相关业务错误码」栏原先把「媒资不存在、已删除」放在
 * {@code 20015} 里。按权威顺序（{@code DESIGN-CONTRACT} &gt; 03 分册）应取契约 §2.4
 * 三分法第 1 行：「访问<b>路径上的资源</b>（{@code GET/PUT/DELETE /xxx/{id}}）而该资源
 * 不在我的子树内 → <b>404</b>，不暴露存在性」。且 F-42 已就同一形状在课程 / 图文资料 / 课时
 * 三处定案为 404 —— 本条是第四处，<b>判得出来，不是偏好</b>。
 *
 * <p>不这么做的后果与 F-42 逐字相同：拿到 {@code 20015} = 这个 id 在本租户不存在，
 * 拿到 403 = 存在但你看不到，逐个 id 试一遍就能分出「哪些存在」。
 * 而 03-01 §7.2 恰恰用「雪花 ID 同租户内时间相邻、可近邻枚举」论证过详情接口不能下发直链。
 *
 * <h2>三个出口，各自答一个问题</h2>
 * <table border="1">
 *   <caption>五个接口共用的分界</caption>
 *   <tr><th>情形</th><th>返回</th><th>为什么</th></tr>
 *   <tr><td>不存在 / 已删除 / 跨租户</td><td><b>404</b></td><td>不暴露存在性</td></tr>
 *   <tr><td>存在，但既非我所有、也没授权给我</td><td><b>404</b></td><td>与上一行<b>同一个结果</b>，否则可探测</td></tr>
 *   <tr><td>可见，但 {@code owner_node_id ≠ 我的节点}</td><td><b>403</b></td>
 *       <td>§7.4/§7.5/§7.6 逐字「仅被授权者只读，写操作返回 403」。<br>
 *           这里<b>不</b>再收敛成 404：我既然被授权，就已经知道它存在，没有存在性可泄露</td></tr>
 *   <tr><td>是我的，但状态不满足前置条件</td><td><b>20015</b></td><td>由各接口自己判，不在本类</td></tr>
 * </table>
 *
 * <p><b>{@code 20015} 在本类里一次都不抛</b> —— 它收窄成「状态不满足前置条件」
 * 加上 §7.1 请求体里那个 {@code videoId}（param-addressed，F-42 的边界）。
 */
@Component
public class VodVideoAccessGuard {

    private final VodVideoMapper videoMapper;
    private final ResourceGrantReader grantReader;
    private final CurrentNodeProvider currentNodeProvider;
    private final OrgRootGuard orgRootGuard;

    public VodVideoAccessGuard(VodVideoMapper videoMapper,
                               ResourceGrantReader grantReader,
                               CurrentNodeProvider currentNodeProvider,
                               OrgRootGuard orgRootGuard) {
        this.videoMapper = videoMapper;
        this.grantReader = grantReader;
        this.currentNodeProvider = currentNodeProvider;
        this.orgRootGuard = orgRootGuard;
    }

    /** 当前登录人所在节点；取不到抛 400（绝不退化为「不加过滤」，契约 §7.1）。 */
    public Long myNodeId() {
        return currentNodeProvider.requireCurrentNodeId();
    }

    /**
     * 媒资写操作仅机构根 —— <b>判定已提到 {@link OrgRootGuard}，三个域共用一份</b>。
     *
     * <p>本方法保留为薄转发：{@code vod} 域内已有 4 处调用点，改成到处 import
     * {@code OrgRootGuard} 只是把同一件事换个写法，而留一个域内入口便于以后
     * 给媒资加域特有的前置。<b>判定逻辑不在这里，不要在这里加分支</b>。
     */
    public void assertOrgRoot() {
        orgRootGuard.assertOrgRoot("媒资");
    }

    /** 我是不是 owner（<b>严格相等</b>，契约 §2.5 规则 8）。 */
    public boolean isOwned(VodVideo video, Long myNodeId) {
        return video.getOwnerNodeId() != null && video.getOwnerNodeId().equals(myNodeId);
    }

    /**
     * 可见 = 自有 ∪ 被显式授权且在有效期内（03-03 §0.2）。
     * <b>不回溯祖先链、不向下展开</b> —— 那是 {@link ResourceGrantReader} 保证的。
     */
    public boolean isVisible(VodVideo video, Long myNodeId) {
        return isOwned(video, myNodeId)
                || grantReader.hasGrant(ResourceType.VIDEO, video.getId(), myNodeId);
    }

    /**
     * <b>路径上的</b>媒资，读用。不存在 / 不可见一律 404。
     *
     * <p>本模块五个接口里没有「只读路径媒资」的场景（列表走批量、其余三个都是写），
     * 保留它是给模块 12 预备的同口径入口 —— 那时若各写各的，两处的存在性口径必然写歧。
     */
    public VodVideo loadVisibleByPath(Long videoId) {
        VodVideo video = videoId == null ? null : videoMapper.selectById(videoId);
        if (video == null || !isVisible(video, myNodeId())) {
            throw BizException.notFound(videoId);
        }
        return video;
    }

    /**
     * <b>路径上的</b>媒资，写用。不存在 / 不可见 → 404；可见但非 owner → 403。
     *
     * <p><b>两步不能合并成一步</b>：合并后「不是我的」会统一成 404，
     * 而 §7.4/§7.5/§7.6 逐字要求被授权者写操作返回 403 —— 那是<b>功能级拒绝</b>
     * （我有这个资源的读权、没有写权），与「不暴露存在性」不是一件事。
     */
    public VodVideo loadOwnedByPath(Long videoId) {
        Long myNodeId = myNodeId();
        VodVideo video = videoId == null ? null : videoMapper.selectById(videoId);
        if (video == null || !isVisible(video, myNodeId)) {
            throw BizException.notFound(videoId);
        }
        if (!isOwned(video, myNodeId)) {
            throw BizException.forbidden();
        }
        return video;
    }
}
