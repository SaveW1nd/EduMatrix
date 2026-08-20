package com.edumatrix.org.grant.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.grant.dto.GrantValidityUpdateReq;
import com.edumatrix.org.grant.entity.OrgResourceGrant;
import com.edumatrix.org.grant.mapper.GrantValidityMapper;
import com.edumatrix.org.grant.mapper.OrgResourceGrantMapper;
import com.edumatrix.org.grant.vo.GrantValidityUpdatedVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;

/**
 * 接口 40 修改授权有效期（03-02 §9.4）。<b>原地更新，一行都不删。</b>
 *
 * <h2>⚠ 本类的构造器里<b>没有</b> {@code GrantWriteService} 与 {@code GrantRevokeService}</h2>
 * <p>这不是疏忽，是这条规则的<b>执行机制</b>。§9.4「为什么需要独立接口」逐字：
 * 没有它，续期只能「撤销 + 重新授权」，而<b>撤销强制级联整棵子树</b>——
 * 给一个教师改一下到期日，会连带清空他名下<b>全部学员</b>的授权，
 * <b>续期反而制造了一次事故</b>。
 *
 * <p>把那两个 Service 注进来，「撤销 + 重授」就成了一个随手可写的实现；
 * 不注进来，想写也写不出来。<b>要破坏这条规则得先改构造器</b>，
 * 而那一步会撞上这段注释。
 *
 * <h2>校验顺序：子树判定提到第 1 位（F-86，明知地偏离 §9.4 的表）</h2>
 * <p>§9.4 的校验表把「授权行存在」列为第 1、「目标 ∈ 我的子树」列为第 2。
 * <b>照那个顺序会造出一个可探测面</b>：对一个<b>不在我子树内</b>的节点发请求，
 * <ul>
 *   <li>该节点<b>有</b>这条授权 → 走到第 2 条 → {@code 10302}；
 *   <li>该节点<b>没有</b>这条授权 → 第 1 条就拦下 → {@code 10307}。
 * </ul>
 * <p>两个码可区分，于是<b>能凭错误码枚举别人节点持有哪些资源</b> ——
 * 与 {@code 10301} 那条防探测（契约 §2.5 规则 1、F-42）是同一件事。
 * 故本实现先判子树：<b>越界一律 {@code 10302}，不泄露那条授权在不在</b>。
 *
 * <p>§9.4 的表是<b>校验项清单</b>，不必然是执行顺序 —— 但这是我的解读，
 * 已按「明知地推翻」登记，需方可推翻。
 */
@Service
public class GrantValidityService {

    private final OrgResourceGrantMapper grantMapper;
    private final GrantValidityMapper validityMapper;
    private final OrgNodeMapper nodeMapper;
    private final ResourceOwnerChecker ownerChecker;
    private final SubtreeScopeHelper subtreeScope;
    private final CurrentNodeProvider currentNodeProvider;

    // ⚠ 不要在这里加 GrantWriteService / GrantRevokeService —— 见类注释。
    public GrantValidityService(OrgResourceGrantMapper grantMapper,
                                GrantValidityMapper validityMapper,
                                OrgNodeMapper nodeMapper,
                                ResourceOwnerChecker ownerChecker,
                                SubtreeScopeHelper subtreeScope,
                                CurrentNodeProvider currentNodeProvider) {
        this.grantMapper = grantMapper;
        this.validityMapper = validityMapper;
        this.nodeMapper = nodeMapper;
        this.ownerChecker = ownerChecker;
        this.subtreeScope = subtreeScope;
        this.currentNodeProvider = currentNodeProvider;
    }

    @Transactional(rollbackFor = Exception.class)
    public GrantValidityUpdatedVO updateValidity(GrantValidityUpdateReq req) {
        Long myNodeId = currentNodeProvider.requireCurrentNodeId();
        ResourceType type = ResourceType.of(req.getResourceType()).orElseThrow();

        // 校验 1（F-86 提前）：目标 ∈ 我的子树 —— 越界一律 10302，不泄露授权行在不在
        if (!subtreeScope.isInSubtree(myNodeId, req.getTargetNodeId())) {
            throw new BizException(ErrorCode.GRANT_TARGET_OUT_OF_SUBTREE,
                    null, req.getTargetNodeId(), null);
        }

        // 校验 2：授权行存在且未撤销
        OrgResourceGrant row = locate(type, req.getResourceId(), req.getTargetNodeId());
        if (row == null) {
            throw new BizException(ErrorCode.GRANT_RECORD_NOT_FOUND);
        }

        // 校验 3：我仍【可再下发】该资源（契约 §2.5 规则 1 + 规则 9）
        if (!ownerChecker.canRegrant(type, req.getResourceId(), myNodeId)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND_OR_NO_GRANT_RIGHT);
        }

        LocalDateTime newStart = req.validStartPresent() ? req.getValidStart() : row.getValidStart();
        LocalDateTime requestedEnd = req.validEndPresent() ? req.getValidEnd() : row.getValidEnd();

        // 校验 4：validStart 必须早于 validEnd
        if (newStart != null && requestedEnd != null && !newStart.isBefore(requestedEnd)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "生效时间必须早于失效时间");
        }

        // 校验 5：截断到我自己的上界（不报错）
        LocalDateTime cap = myCapFor(type, req.getResourceId(), myNodeId);
        LocalDateTime effectiveEnd = earlier(requestedEnd, cap);
        boolean truncated = !java.util.Objects.equals(effectiveEnd, requestedEnd);

        Long operatorId = TenantHelper.getUserId();
        validityMapper.updateValidity(row.getId(),
                req.validStartPresent(), newStart,
                req.validEndPresent() || truncated, effectiveEnd, operatorId);

        int cascadeTruncated = cascadeIfShortened(type, req, row, effectiveEnd, operatorId);

        GrantValidityUpdatedVO vo = new GrantValidityUpdatedVO();
        vo.setGrantId(row.getId());
        vo.setValidStart(newStart);
        vo.setValidEnd(effectiveEnd);
        vo.setValidEndTruncated(truncated);
        vo.setEffectiveValidEnd(effectiveEnd);
        vo.setCascadeTruncatedCount(cascadeTruncated);
        return vo;
    }

    /**
     * <b>缩短才级联，延长一行不动</b>（PRD FR-1 规则 4、§9.4）。
     *
     * <h2>「缩短」怎么判 —— {@code null} 是最晚，不是最早</h2>
     * <p>{@code valid_end = null} 表示<b>永久有效</b>，也就是<b>比任何具体日期都晚</b>。
     * 于是：
     * <ul>
     *   <li>原值 {@code null} → 新值具体日期：<b>是缩短</b>（从永久收到某天）；
     *   <li>原值具体日期 → 新值 {@code null}：<b>是延长</b>（放宽到永久），不级联；
     *   <li>两个都是具体日期：比大小。
     * </ul>
     * <p>把 {@code null} 当成「最早」会让第一种情况被判成延长而不级联 ——
     * 结果是上级从永久收到了 06-30，而<b>子树整片仍然永久有效</b>，
     * 一次「收紧」实际什么都没收紧，且不报错。
     */
    private int cascadeIfShortened(ResourceType type, GrantValidityUpdateReq req,
                                   OrgResourceGrant row, LocalDateTime effectiveEnd,
                                   Long operatorId) {
        if (!isShortened(row.getValidEnd(), effectiveEnd)) {
            return 0;
        }
        OrgNode node = nodeMapper.selectById(req.getTargetNodeId());
        if (node == null) {
            // 【走不到，但不能静默】上面的子树判定已经过了，节点必然存在。
            // 真走到这里说明两次读之间节点被删了 —— 那时【返回 0 是错的】：
            // 它的表现与「本来就没有子树行要截断」完全一样，
            // 而实际是「该截断的没截断」，留下时间维度悬挂。响亮失败
            throw new IllegalStateException("子树判定已通过但节点读不到，"
                    + "两次读之间该节点被删除？targetNodeId=" + req.getTargetNodeId());
        }
        String prefix = node.getAncestors() == null || node.getAncestors().isEmpty()
                ? String.valueOf(node.getId())
                : node.getAncestors() + "," + node.getId();
        return validityMapper.truncateSubtree(type.code(), req.getResourceId(),
                req.getTargetNodeId(), prefix, effectiveEnd, operatorId);
    }

    /** 见 {@link #cascadeIfShortened} 的注释：{@code null} 是最晚。 */
    private static boolean isShortened(LocalDateTime oldEnd, LocalDateTime newEnd) {
        if (newEnd == null) {
            return false;               // 改为永久 = 放宽
        }
        return oldEnd == null || newEnd.isBefore(oldEnd);
    }

    /** 按业务键定位那一行（{@code deleted_at = 0} 由 {@code @TableLogic} 追加）。 */
    private OrgResourceGrant locate(ResourceType type, Long resourceId, Long targetNodeId) {
        List<OrgResourceGrant> rows = grantMapper.selectList(
                new LambdaQueryWrapper<OrgResourceGrant>()
                        .eq(OrgResourceGrant::getResourceType, type.code())
                        .eq(OrgResourceGrant::getResourceId, resourceId)
                        .eq(OrgResourceGrant::getTargetNodeId, targetNodeId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 我自己持有该资源的 {@code valid_end}；我是 owner 时<b>不受限</b>（契约 §2.5 规则 7 末句）。
     */
    private LocalDateTime myCapFor(ResourceType type, Long resourceId, Long myNodeId) {
        if (ownerChecker.isOwner(type, resourceId, myNodeId)) {
            return null;
        }
        OrgResourceGrant mine = locate(type, resourceId, myNodeId);
        return mine == null ? null : mine.getValidEnd();
    }

    /** 取更早的那个；{@code null} 视为「永久」即最晚。 */
    private static LocalDateTime earlier(LocalDateTime requested, LocalDateTime cap) {
        if (cap == null) {
            return requested;
        }
        if (requested == null || requested.isAfter(cap)) {
            return cap;
        }
        return requested;
    }
}
