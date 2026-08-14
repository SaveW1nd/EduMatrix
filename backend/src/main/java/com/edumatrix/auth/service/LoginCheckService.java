package com.edumatrix.auth.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.edumatrix.auth.entity.AuthUser;
import com.edumatrix.auth.mapper.AuthOrgMapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.frozen.mapper.FrozenNodeMapper;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodeAncestorCache;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.tenant.TenantHelper;

/**
 * 账号能不能进来 —— 判定链的后四步（⑤⑥⑦⑧），<b>登录与刷新令牌共用同一份</b>。
 *
 * <pre>
 * ⑤ sys_user.status = 1            → 10005  账号级封禁（仅超管可置）
 * ⑥ 租户停用 / 服务到期            → 10007  平台超管做的处置
 * ⑦ org_student.status = 2         → 10015  机构侧学籍操作
 * ⑧ 本人节点或其上级管理员被停用   → 10017  机构管理员做的组织侧冻结
 * </pre>
 *
 * <h2>四个码不得合并</h2>
 * <p>00-通用约定 §9.2 与契约 §2.3 都写明它们是<b>四个不同的原因、四拨不同的责任人</b>：
 * {@code 10007} 找平台、{@code 10005} 找超管、{@code 10015} 找教务、{@code 10017} 找机构管理员。
 * 合并任意两个，用户就会走错求助路径 —— 而这类错误没有任何技术手段能在事后发现，
 * 只表现为「客服说不归他们管」。
 *
 * <h2>为什么刷新令牌也要跑这四步</h2>
 * <p>00-通用约定 §2.2 规则 6 与 03-01 §1.3 的错误码表（{@code 10005/10007/10017}）：
 * <b>停用后不许续签</b>。少跑这一段的话，一个已被停用的账号可以靠 7 天有效期的
 * refreshToken 无限续命，冻结集拦得住 accessToken 却拦不住这条路。
 */
@Service
public class LoginCheckService {

    /** {@code sys_user.status}：1 = 账号级封禁。 */
    private static final int USER_STATUS_DISABLED = 1;
    /** {@code org_student.status}：2 = 毕业归档（契约 §5 student_status）。 */
    private static final int STUDENT_STATUS_ARCHIVED = 2;

    private final AuthOrgMapper authOrgMapper;
    private final FrozenNodeMapper frozenNodeMapper;
    private final NodeAncestorCache nodeAncestorCache;

    public LoginCheckService(AuthOrgMapper authOrgMapper,
                             FrozenNodeMapper frozenNodeMapper,
                             NodeAncestorCache nodeAncestorCache) {
        this.authOrgMapper = authOrgMapper;
        this.frozenNodeMapper = frozenNodeMapper;
        this.nodeAncestorCache = nodeAncestorCache;
    }

    /** 判定链⑤⑥⑦⑧；任一不通过即抛对应业务码。 */
    public void assertLoginable(AuthUser user) {
        assertAccountNotBanned(user);
        assertTenantActive(user);
        // ⑦⑧ 读 org_student / org_node，两张表都带 tenant_id：登录那一刻还没有会话，
        // 必须显式提供租户上下文（契约 §2.8 规则 1/3：绝不退化为忽略租户条件）
        TenantHelper.runWithTenant(user.getTenantId(), () -> {
            assertStudentNotArchived(user);
            assertNodeNotDisabled(user);
        });
    }

    /** ⑤ {@code sys_user.status = 1} → {@code 10005}。<b>本模块只读它，任何路径下都不写。</b> */
    private void assertAccountNotBanned(AuthUser user) {
        if (user.getStatus() != null && user.getStatus() == USER_STATUS_DISABLED) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED_OR_LOCKED);
        }
    }

    /**
     * ⑥ 租户停用或到期 → {@code 10007}（PRD F1-1 规则 3/4：数据保留不删除，续期后恢复）。
     *
     * <p>平台超管跳过：他的 {@code tenant_id = 0}，{@code sys_tenant} 里没有这一行，
     * 而「机构服务到期」这件事对平台自己不成立。
     */
    private void assertTenantActive(AuthUser user) {
        if (user.isSuperAdmin()) {
            return;
        }
        AuthOrgMapper.TenantRow tenant = authOrgMapper.selectTenant(user.getTenantId());
        if (tenant == null || tenant.isDisabledOrExpired(LocalDateTime.now())) {
            // 租户行查不到也按 10007：账号挂在一个不存在的机构上，登录不该成功
            throw new BizException(ErrorCode.TENANT_DISABLED_OR_EXPIRED);
        }
    }

    /**
     * ⑦ 学籍已归档 → {@code 10015}。
     *
     * <p>只判 {@code status = 2}（毕业归档）—— 00-通用约定 §9.2 对 {@code 10015} 的定义
     * 逐字如此。{@code status = 1}（已退课）不在其中，不擅自扩大。
     */
    private void assertStudentNotArchived(AuthUser user) {
        if (!user.isStudent()) {
            return;
        }
        Integer studentStatus = authOrgMapper.selectStudentStatus(user.getNodeId());
        if (studentStatus != null && studentStatus == STUDENT_STATUS_ARCHIVED) {
            throw new BizException(ErrorCode.ACCOUNT_ARCHIVED);
        }
    }

    /**
     * ⑧ 组织侧停用的<b>两段校验</b>，缺一不可（契约 §2.3 / PRD F1-2 规则 9）→ {@code 10017}。
     *
     * <p>①本人所在节点 {@code status = 1}（教师/学生的「仅本人」由此生效）；
     * ②祖先链中有 {@code node_type = 1 且 status = 1} 的管理员（分支冻结由此生效）。
     *
     * <p><b>登录走的是查库（{@link FrozenNodeMapper}），不是冻结集</b>：
     * 冻结集是为了让「已在线的人」立刻失效（04 §B 规则 3），而登录本就要查库、频率也低；
     * 更要紧的是<b>库是权威</b> —— 万一 Redis 被清空过，冻结集会漏，而登录这道闸口不能漏。
     * 两条路径判的是同一条 SQL，不可能给出不同结果。
     *
     * <p>祖先链<b>现取</b>（{@code NodeAncestorCache}），绝不来自 Token。
     */
    private void assertNodeNotDisabled(AuthUser user) {
        Long nodeId = user.getNodeId();
        if (nodeId == null) {
            return;
        }
        List<Long> ancestorIds = NodePath.parseAncestorIds(nodeAncestorCache.get(nodeId));
        if (frozenNodeMapper.selectFirstDisabled(nodeId, ancestorIds) != null) {
            throw new BizException(ErrorCode.NODE_OR_ANCESTOR_DISABLED);
        }
    }
}
