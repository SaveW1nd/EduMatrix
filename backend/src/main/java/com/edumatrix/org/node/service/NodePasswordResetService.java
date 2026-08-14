package com.edumatrix.org.node.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class NodePasswordResetService {

    /** §3.6：留空时服务端随机生成 <b>≥12 位</b>强口令。取 16 位，与 03-01 §2.5 同值。 */
    private static final int GENERATED_PASSWORD_LENGTH = 16;

    /** 去掉了易混字符（{@code I l 1 O 0}）：管理员要口头/短信转告本人。 */
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "!@#$%^&*-_=+";

    private final OrgNodeMapper nodeMapper;
    private final NodeAccountMapper accountMapper;
    private final SubtreeScopeHelper subtreeScopeHelper;
    private final CurrentNodeResolver currentNodeResolver;
    private final PasswordHasher passwordHasher;
    private final SessionRevoker sessionRevoker;

    private final SecureRandom random = new SecureRandom();

    public NodePasswordResetService(OrgNodeMapper nodeMapper,
                                    NodeAccountMapper accountMapper,
                                    SubtreeScopeHelper subtreeScopeHelper,
                                    CurrentNodeResolver currentNodeResolver,
                                    PasswordHasher passwordHasher,
                                    SessionRevoker sessionRevoker) {
        this.nodeMapper = nodeMapper;
        this.accountMapper = accountMapper;
        this.subtreeScopeHelper = subtreeScopeHelper;
        this.currentNodeResolver = currentNodeResolver;
        this.passwordHasher = passwordHasher;
        this.sessionRevoker = sessionRevoker;
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

        String plain = hasText(req.getNewPassword())
                ? assertStrongEnough(req.getNewPassword())
                : generatePassword();

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

    /**
     * 「8~20 位且同时含字母与数字」——长度已由 DTO 的 {@code @Size} 拦住，
     * 这里判跨字符的那一半（正则表达可读性差）。不合规返回 <b>400</b>，不是业务码
     * （§3.6：「密码格式不合规返回 400」）。
     */
    private static String assertStrongEnough(String raw) {
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码须同时包含字母与数字");
        }
        return raw;
    }

    /**
     * 服务端随机强口令。
     *
     * <p><b>严禁以手机号后 6 位等可由账号推导的值作兜底</b>（§3.6 原文、PRD F1-3 规则 3）：
     * {@code username} 即手机号，同源意味着<b>拿到名单即可登录任意账号</b>。
     * 也<b>不设任何固定默认密码</b>——固定常量会出现在文档与工单里，
     * 攻击者拿到用户名列表即可批量撞库命中所有「已重置未改密」的账号。
     *
     * <p>四类字符各保底一个后打乱：只按字符池均匀抽样时，
     * 16 位里一个数字都没有的概率虽小却<b>不为零</b>，而那种口令过不了 §1.2 的登录侧校验。
     */
    private String generatePassword() {
        String pool = UPPER + LOWER + DIGIT + SYMBOL;
        List<Character> chars = new ArrayList<>(GENERATED_PASSWORD_LENGTH);
        chars.add(UPPER.charAt(random.nextInt(UPPER.length())));
        chars.add(LOWER.charAt(random.nextInt(LOWER.length())));
        chars.add(DIGIT.charAt(random.nextInt(DIGIT.length())));
        chars.add(SYMBOL.charAt(random.nextInt(SYMBOL.length())));
        while (chars.size() < GENERATED_PASSWORD_LENGTH) {
            chars.add(pool.charAt(random.nextInt(pool.length())));
        }
        Collections.shuffle(chars, random);
        StringBuilder sb = new StringBuilder(chars.size());
        for (char c : chars) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
