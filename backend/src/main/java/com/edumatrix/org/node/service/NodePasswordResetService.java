package com.edumatrix.org.node.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.edumatrix.common.account.InitialPasswordFactory;
import com.edumatrix.common.account.PasswordHasher;
import com.edumatrix.common.account.SessionRevoker;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.node.dto.NodePasswordResetReq;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.NodeAccountMapper;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.vo.NodePasswordResetVO;

/**
 * 重置人员密码（03-02 §3.6）。
 *
 * <p><b>按节点 ID 而非按人员类型分三个接口</b>：每个节点都是一个人（契约 §2.3），
 * 管理员 / 教师 / 学生的重置逻辑完全一致，没有分三份的理由。
 *
 * <h2>教师「仅限名下学员」不需要额外一段代码</h2>
 * <p>§3.6 的权限栏写着 {@code teacher}「仅限其名下学员，即该节点是<b>自己的直接子节点</b>」。
 * 而契约 §2.3 结构约束 1：<b>教师节点下只能挂学生</b> —— 于是教师的<b>子树恰好等于
 * 其直接子节点</b>，{@code SubtreeScopeHelper#assertInSubtree} 这一句就<b>正好</b>
 * 是那条限制。再写一遍「必须是直接子节点」是同一规则的第二份实现，
 * 而两份迟早会分叉。
 *
 * <h2>口令一律走 {@code PasswordHasher}</h2>
 * <p>不自己 {@code new BCryptPasswordEncoder}：cost 分叉会让新旧密文强度不同，
 * 而 BCrypt 把 cost 编码在密文里，两边都验得过 —— <b>不报错、不失败，
 * 只是安全强度悄悄回退</b>（{@code PasswordHasher} 类注释）。
 *
 * <p><b>口令的生成与格式校验同理，已收敛到 {@code common/account/InitialPasswordFactory}</b>：
 * 本类此前有两个私有方法（{@code generatePassword} / {@code assertStrongEnough}），
 * 与模块 07 三个建人接口的实现<b>逐字相同</b>。两份并存时「改一份忘了另一份」不会有任何
 * 东西报错，只是同一条口令规则在两条路径上一严一松 —— <b>而宽的那条不报错</b>。
 * 模块 07 落地时已合并为一处，本类改为委派。
 */
@Service
public class NodePasswordResetService {

    private final OrgNodeMapper nodeMapper;
    private final NodeAccountMapper accountMapper;
    private final SubtreeScopeHelper subtreeScopeHelper;
    private final CurrentNodeResolver currentNodeResolver;
    private final PasswordHasher passwordHasher;
    private final SessionRevoker sessionRevoker;
    private final InitialPasswordFactory initialPasswordFactory;

    public NodePasswordResetService(OrgNodeMapper nodeMapper,
                                    NodeAccountMapper accountMapper,
                                    SubtreeScopeHelper subtreeScopeHelper,
                                    CurrentNodeResolver currentNodeResolver,
                                    PasswordHasher passwordHasher,
                                    SessionRevoker sessionRevoker,
                                    InitialPasswordFactory initialPasswordFactory) {
        this.nodeMapper = nodeMapper;
        this.accountMapper = accountMapper;
        this.subtreeScopeHelper = subtreeScopeHelper;
        this.currentNodeResolver = currentNodeResolver;
        this.passwordHasher = passwordHasher;
        this.sessionRevoker = sessionRevoker;
        this.initialPasswordFactory = initialPasswordFactory;
    }

    @Transactional(rollbackFor = Exception.class)
    public NodePasswordResetVO reset(Long nodeId, NodePasswordResetReq req) {
        // §3.6 数据权限：不在子树内返回 404（不暴露存在性）。
        // 教师的「仅限名下学员」由这一句一并承担，见类注释
        Long myNodeId = currentNodeResolver.requireCurrentNodeId();
        subtreeScopeHelper.assertInSubtree(myNodeId, nodeId);
        // §3.6：【不得对自己执行】→ 10012。改自己的密码走 03-01 §1.6
        if (nodeId.equals(myNodeId)) {
            throw new BizException(ErrorCode.OPERATION_ON_SELF_FORBIDDEN);
        }

        OrgNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }
        Long userId = node.getRefUserId();
        if (userId == null) {
            // ref_user_id 是 NOT NULL 且「每个节点都是一个人」，走到这里说明数据已经坏了
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }

        // 口令规则（8~20 位含字母数字 / 留空生成 ≥12 位强口令 / 严禁可由账号推导的默认值）
        // 一律走 common/account/InitialPasswordFactory —— 本类此前有一份逐字相同的私有实现，
        // 与模块 07 建人那三个接口的实现并存；【已合并到那一处，本类改为委派】
        String plain = initialPasswordFactory.resolve(req.getNewPassword());

        // §3.6 事务内三件事：写 password → 置 pwd_reset_flag=1 → 作废该用户全部在线 Token。
        // 前两件是同一行的两列，一条 UPDATE 完成（分两条只会多留一个「改了密码没置标志」的中间态）
        accountMapper.resetPassword(userId, passwordHasher.hash(plain), TenantHelper.getUserId());

        // 【在事务内调用】是分册的原文（§3.6：「事务内：…→ 作废该用户全部在线 Token → …」）。
        // SessionRevoker 的类注释也写明「在事务内调用或先提交事务再调用皆可（Redis 不参与回滚），
        // 但绝不可先返回成功、再异步作废」。方向与冻结集一致：宁可多作废一瞬，不可漏放一瞬
        sessionRevoker.revokeAllSessions(userId);

        NodeAccountMapper.UserBriefRow account = accountMapper.selectUserBrief(userId);

        NodePasswordResetVO vo = new NodePasswordResetVO();
        vo.setNodeId(nodeId);
        vo.setUserId(userId);
        vo.setRealName(account == null ? node.getNodeName() : account.getRealName());
        // 明文仅在本次响应返回一次，不落库、不可再查（§3.6 响应字段说明）
        vo.setNewPassword(plain);
        vo.setMustChangeOnNextLogin(true);
        return vo;
    }
}
