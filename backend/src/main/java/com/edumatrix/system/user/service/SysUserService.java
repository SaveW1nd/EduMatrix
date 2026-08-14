package com.edumatrix.system.user.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.account.PasswordHasher;
import com.edumatrix.common.account.SessionRevoker;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.subtree.NodeAncestorCache;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.system.user.dto.UserCreateReq;
import com.edumatrix.system.user.dto.UserPageQuery;
import com.edumatrix.system.user.dto.UserPasswordResetReq;
import com.edumatrix.system.user.dto.UserStatusReq;
import com.edumatrix.system.user.dto.UserUpdateReq;
import com.edumatrix.system.user.entity.SysUser;
import com.edumatrix.system.user.entity.SysUserRole;
import com.edumatrix.system.user.entity.SystemOrgNode;
import com.edumatrix.system.user.mapper.StudentQuotaMapper;
import com.edumatrix.system.user.mapper.SysUserMapper;
import com.edumatrix.system.user.mapper.SysUserRoleMapper;
import com.edumatrix.system.user.mapper.SystemOrgNodeMapper;
import com.edumatrix.system.user.vo.PasswordResetVO;
import com.edumatrix.system.user.vo.UserCreatedVO;
import com.edumatrix.system.user.vo.UserListVO;

/**
 * 用户管理（03-01 §2.1~§2.6）。
 *
 * <h2>写接口（§2.2~§2.6）一律仅 {@code super_admin}，这是本组的核心约束</h2>
 * <p>§2 导语原文：本组写接口「用途限定为<b>平台级账号维护</b>（超管自身账号、
 * 跨租户排障时的账号处置）。<b>机构侧人员的建、改、删、重置密码、启停用一律走
 * 02-组织机构分册的 {@code /api/v1/org/**}</b>。只有 2.1 分页查询保留 org_admin」。
 *
 * <p><b>为什么必须收敛成一条路径</b>（§2 导语逐条给了理由，此处记最要紧的一条）：
 * 本组的创建<b>不写 {@code org_teacher} / {@code org_student} 档案</b>，
 * 经这条路径建出的教师在 {@code /org/teachers} 列表里查不到、无工号无科目 ——
 * 正是 PRD F1-3 规则 1 明令禁止的孤儿数据（「有节点无档案」）。
 * 而 {@code /org/**} 的建人接口是<b>三写一事务</b>，任一失败整体回滚。
 *
 * <p>这条约束<b>不靠代码里的 if</b>，靠 {@code sys_role_menu} 的初始化数据：
 * {@code system:user:add/edit/remove/resetPwd/status} 五个标识只绑了 super_admin，
 * 于是 {@code @SaCheckPermission} 对 org_admin 天然 403。
 */
@Service
public class SysUserService {

    /** §2.5 服务端随机口令长度（原文：≥12 位）。 */
    private static final int GENERATED_PASSWORD_LENGTH = 16;
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "!@#$%^&*-_=+";

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SystemOrgNodeMapper orgNodeMapper;
    private final StudentQuotaMapper studentQuotaMapper;
    private final PlatformNodeWriter platformNodeWriter;
    private final SubtreeScopeHelper subtreeScopeHelper;
    private final NodeAncestorCache nodeAncestorCache;
    private final PasswordHasher passwordHasher;
    private final SessionRevoker sessionRevoker;

    private final SecureRandom random = new SecureRandom();

    public SysUserService(SysUserMapper sysUserMapper,
                          SysUserRoleMapper sysUserRoleMapper,
                          SystemOrgNodeMapper orgNodeMapper,
                          StudentQuotaMapper studentQuotaMapper,
                          PlatformNodeWriter platformNodeWriter,
                          SubtreeScopeHelper subtreeScopeHelper,
                          NodeAncestorCache nodeAncestorCache,
                          PasswordHasher passwordHasher,
                          SessionRevoker sessionRevoker) {
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.orgNodeMapper = orgNodeMapper;
        this.studentQuotaMapper = studentQuotaMapper;
        this.platformNodeWriter = platformNodeWriter;
        this.subtreeScopeHelper = subtreeScopeHelper;
        this.nodeAncestorCache = nodeAncestorCache;
        this.passwordHasher = passwordHasher;
        this.sessionRevoker = sessionRevoker;
    }

    // =====================================================================
    // §2.1 分页查询用户 —— 本组唯一对 org_admin 开放的接口
    // =====================================================================

    public PageResult<UserListVO> page(UserPageQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .like(hasText(query.getUsername()), SysUser::getUsername, query.getUsername())
                .like(hasText(query.getRealName()), SysUser::getRealName, query.getRealName())
                .eq(hasText(query.getPhone()), SysUser::getPhone, query.getPhone())
                .eq(query.getUserType() != null, SysUser::getUserType, query.getUserType())
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
                .ge(hasText(query.getBeginTime()), SysUser::getCreateTime, query.getBeginTime())
                .le(hasText(query.getEndTime()), SysUser::getCreateTime, query.getEndTime())
                .orderByDesc(SysUser::getCreateTime)
                .orderByAsc(SysUser::getId);

        if (!applySubtreeScope(wrapper, query)) {
            // 子树解析为空集：SubtreeScopeHelper 已记 ERROR。按「什么都看不到」返回，
            // 绝不退化为不加过滤（那就是一次全量泄露）
            return PageResult.empty();
        }

        Page<SysUser> page = new Page<>(
                PageResult.normalizePageNum(query.getPageNum()),
                PageResult.normalizePageSize(query.getPageSize()));
        Page<SysUser> result = sysUserMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), toListVOs(result.getRecords()));
    }

    /**
     * 数据权限（§2.1 数据权限栏 + §0.2 唯一规则）。
     *
     * <p><b>方法名刻意不叫 {@code applyDataScope}</b>：{@code DataScope} 是<b>已被契约否决的概念</b>
     * （{@code sys_role} 早已删掉 {@code data_scope} 列，契约 §3「操作权限由角色定、数据范围由树定」），
     * 用它命名会把后来者往「数据权限分档」上引，而本系统压根没有第二套过滤逻辑 ——
     * 与 {@code common/subtree} 这个包为什么不叫 {@code datascope} 是同一条理由。
     * {@code scripts/check_consistency.py} 的 C8 把 {@code DataScope} 列为禁用词。
     *
     * <pre>
     * org_admin  ：仅自身节点子树内的账号（叠加租户插件的 tenant_id 过滤）
     *              传了 nodeId 且不在自身子树内 → 10107（三分法，见下）
     * super_admin：可传 tenantId 查指定租户；不传则查【平台级账号】(tenant_id = 0)
     *              传了 nodeId 则限定为该节点及其子树
     * </pre>
     *
     * <p><b>超管这一支必须显式写租户条件</b>：{@code TenantHelper.isSuperAdminSession()}
     * 让租户处理器的 {@code ignoreTable} 返回 true（契约 §2.9 的「超管整体放行」通道），
     * 插件因此<b>不注入任何 {@code tenant_id} 条件</b>。不补这一句的话，
     * 超管不传 {@code tenantId} 会列出全平台每一个账号 —— 而 §2.1 说的是"平台级账号"。
     * 写的是等值条件，<b>不是</b> {@code OR tenant_id = 0}（检查① grep 的是后者）。
     *
     * @return {@code false} 表示数据权限解析为空集，调用方应直接返回空页
     */
    private boolean applySubtreeScope(LambdaQueryWrapper<SysUser> wrapper, UserPageQuery query) {
        if (TenantHelper.isSuperAdminSession()) {
            Long tenantId = query.getTenantId() == null
                    ? SystemOrgNode.PLATFORM_ROOT_ID : query.getTenantId();
            wrapper.eq(SysUser::getTenantId, tenantId);
            if (query.getNodeId() == null) {
                return true;
            }
            // 超管不走 SubtreeScopeHelper.isInSubtree 的越界判定：他的 node_id 是平台根
            // 哨兵 0，而 parseAncestorIds 会跳过哨兵，判定恒为 false（该类注释已记此坑）。
            // 超管看得到一切，所以只需按传入节点取子树
            return applySubtreeFilter(wrapper, query.getNodeId());
        }

        Long myNodeId = currentNodeId();
        Long scopeNodeId = query.getNodeId() == null ? myNodeId : query.getNodeId();
        if (query.getNodeId() != null) {
            // 取三分法的 10107，不取 §2.1 参数表写的 403 —— 本字段与 §2.2 的 parentNodeId
            // 是同一形状（调用方在请求里显式选定的一个节点），而 §2.2 用的就是 10107。
            // 同一分册内同形状参数给了两种码，其中一个必然是笔误；判断与依据逐条见
            // UserPageQuery#nodeId 的注释，已登记为 04-实施计划.md §E 的 F-23
            subtreeScopeHelper.assertTargetInSubtree(myNodeId, query.getNodeId());
        }
        return applySubtreeFilter(wrapper, scopeNodeId);
    }

    private boolean applySubtreeFilter(LambdaQueryWrapper<SysUser> wrapper, Long scopeNodeId) {
        // 契约 §2.4 选路表：管理员取整棵子树 = 前缀 LIKE 解析 ID 集合，
        // 再对业务表 WHERE node_id IN (...)。全程无 FIND_IN_SET
        List<Long> nodeIds = subtreeScopeHelper.subtreeNodeIds(scopeNodeId);
        if (nodeIds.isEmpty()) {
            return false;
        }
        wrapper.in(SysUser::getNodeId, nodeIds);
        return true;
    }

    // =====================================================================
    // §2.2 创建用户 —— 同事务落 sys_user + sys_user_role + org_node + org_node_change_log
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public UserCreatedVO create(UserCreateReq req) {
        assertPasswordFormat(req.getPassword());
        assertRolesVisible(req.getRoleIds());

        SystemOrgNode parent = requireParentInMyScope(req.getParentNodeId());
        assertStudentQuota(req.getUserType(), parent.getTenantId());

        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setUserType(req.getUserType());
        user.setRealName(req.getRealName());
        user.setPhone(blankToNull(req.getPhone()));
        user.setAvatar(blankToNull(req.getAvatar()));
        user.setStatus(req.getStatus() == null ? SysUser.STATUS_NORMAL : req.getStatus());
        user.setPwdResetFlag(0);
        user.setRemark(req.getRemark());
        // node_id 是 NOT NULL，而节点要等账号 id 出来才能建（ref_user_id 指向它）。
        // 先占位 0，建完节点立刻回写 —— 两步在同一事务内，中间态不可见
        user.setNodeId(SystemOrgNode.PLATFORM_ROOT_ID);
        // 【必须显式写 tenant_id】超管会话下租户插件整体放行，INSERT 时不注入租户列。
        // 取值来自 parentNodeId 所在节点 —— 契约 §2.8 规则 1「从数据显式取」，
        // 而不是猜一个或沿用会话（超管的会话租户是 0）
        user.setTenantId(parent.getTenantId());
        user.setPassword(passwordHasher.hash(req.getPassword()));
        insertUserOrThrowDuplicate(user);

        Long nodeId = platformNodeWriter.createNode(
                user.getId(), req.getUserType(), req.getRealName(), req.getParentNodeId());
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getNodeId, nodeId));

        replaceUserRoles(user.getId(), parent.getTenantId(), req.getRoleIds());

        SysUser saved = sysUserMapper.selectById(user.getId());
        UserCreatedVO vo = new UserCreatedVO();
        vo.setId(saved.getId());
        vo.setUsername(saved.getUsername());
        vo.setRealName(saved.getRealName());
        vo.setUserType(saved.getUserType());
        vo.setStatus(saved.getStatus());
        vo.setNodeId(nodeId);
        // 恒等，不做映射（契约 §5）。两者并列返回正是为了让一次映射错误当场暴露
        vo.setNodeType(saved.getUserType());
        vo.setParentNodeId(req.getParentNodeId());
        vo.setNodePath(resolveNodePath(nodeId));
        vo.setCreateTime(saved.getCreateTime());
        return vo;
    }

    /**
     * 插入账号并处理用户名冲突。
     *
     * <p><b>不预先查一次"这个用户名是不是被删过"</b> —— {@code uk_username(username, deleted_at)}
     * 已经处理了：逻辑删除写的是毫秒时间戳，同一 {@code username} 因此可容纳任意多条已删除行，
     * <b>同名重建自动放行</b>（契约 §2.2）。这是 {@code deleted_at} 用时间戳而非 0/1 的直接收益：
     * 0/1 方案下同一用户名最多容纳一条已删除行，第二次删除同名用户就撞唯一键。
     *
     * <p>所以这里只需把唯一键冲突翻成 {@code 10001}。
     * {@code uk_tenant_phone} 冲突则是 {@code 10013}（手机号租户内唯一）——
     * 两个唯一键都可能命中，按约束名区分。
     */
    private void insertUserOrThrowDuplicate(SysUser user) {
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("uk_tenant_phone")) {
                throw new BizException(ErrorCode.PHONE_ALREADY_USED);
            }
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
    }

    // =====================================================================
    // §2.3 修改用户
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, UserUpdateReq req) {
        SysUser user = requireUser(userId);
        assertRolesVisible(req.getRoleIds());
        assertNotDemotingSelf(userId, req.getRoleIds());

        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getRealName, req.getRealName())
                .set(SysUser::getPhone, blankToNull(req.getPhone()))
                .set(SysUser::getAvatar, blankToNull(req.getAvatar()))
                .set(SysUser::getRemark, req.getRemark()));

        // §2.3 参数表：realName「同步更新其 org_node.node_name」
        if (user.getNodeId() != null) {
            SystemOrgNode nodeUpdate = new SystemOrgNode();
            nodeUpdate.setId(user.getNodeId());
            nodeUpdate.setNodeName(req.getRealName());
            orgNodeMapper.updateById(nodeUpdate);
        }

        replaceUserRoles(userId, user.getTenantId(), req.getRoleIds());
    }

    // =====================================================================
    // §2.4 删除用户（逻辑删除）
    // =====================================================================

    /**
     * 逻辑删除账号 + 其组织树节点，并作废该账号在线 Token。
     *
     * <p><b>与 02 分册删除接口的语义差别</b>（§2.4 原文，务必分清）：本接口置
     * {@code sys_user.deleted_at}，<b>用户名随之释放</b>（同名账号可再建）；
     * 02 分册接口 10/14/19 只置 {@code sys_user.status = 1}，账号保留、用户名仍被占用。
     * 机构侧删人一律走 02 分册，本接口仅用于平台级账号的彻底注销。
     *
     * <p><b>对已删除用户重复调用同样返回 200（幂等）</b> —— §2.4 响应示例的标题就是这句。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId) {
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        if (user == null) {
            // 幂等：已删除（或从来不存在）时静默成功。
            // 这不与「跨租户返回 404」冲突 —— 两者都不暴露存在性，
            // 而幂等是 §2.4 明确写出的行为
            return;
        }
        assertNotSelf(userId);

        // 子节点保护先于任何写入：10108（§2.4「该节点下若仍有未删除的子节点则拒绝删除」）
        platformNodeWriter.deleteNode(user.getNodeId());

        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId));
        sysUserMapper.deleteById(userId);

        // 作废在线 Token。跨领域能力走 common/account 的 SPI（实现在 auth），
        // 因为检查③禁止 system import auth
        sessionRevoker.revokeAllSessions(userId);
    }

    // =====================================================================
    // §2.5 重置用户密码
    // =====================================================================

    /**
     * 重置密码并强制该账号全部在线会话下线。
     *
     * @return 服务端随机生成口令时返回 {@code initialPassword}（明文仅此一次）；
     *         调用方指定了 {@code newPassword} 时返回 {@code null}（响应 {@code data} 即 null）
     */
    @Transactional(rollbackFor = Exception.class)
    public PasswordResetVO resetPassword(Long userId, UserPasswordResetReq req) {
        requireUser(userId);

        boolean generated = !hasText(req.getNewPassword());
        String rawPassword = generated ? generateStrongPassword() : req.getNewPassword();
        if (!generated) {
            assertPasswordFormat(rawPassword);
        }

        sysUserMapper.resetPassword(userId, passwordHasher.hash(rawPassword));
        // §2.5：「重置后该用户全部在线会话强制下线」
        sessionRevoker.revokeAllSessions(userId);

        return generated ? new PasswordResetVO(rawPassword) : null;
    }

    // =====================================================================
    // §2.6 启用/停用用户
    // =====================================================================

    /**
     * 改 {@code sys_user.status}（<b>账号级封禁</b>，契约 §2.3）。
     *
     * <p>「停用后该用户在线 Token 立即作废，登录返回 {@code 10005}」（§2.6 数据权限栏）。
     * <b>启用不作废</b>：那会把一个刚被恢复的用户莫名其妙踢下线。
     *
     * <p><b>绝不顺手写 {@code org_node.status}</b>：契约 §2.3 用一整段说明那条侧路
     * 为什么被拆掉 —— 「停用可逆、启用不可逆」，账号侧的 1 再也没人改回来。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long userId, UserStatusReq req) {
        requireUser(userId);
        if (req.getStatus() == SysUser.STATUS_DISABLED) {
            assertNotSelf(userId);
        }
        sysUserMapper.updateStatus(userId, req.getStatus());
        if (req.getStatus() == SysUser.STATUS_DISABLED) {
            sessionRevoker.revokeAllSessions(userId);
        }
    }

    // =====================================================================
    // 校验
    // =====================================================================

    private SysUser requireUser(Long userId) {
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        if (user == null) {
            // 跨租户在这里自然收敛成 404（租户插件过滤掉了那一行）——
            // 契约 §2.4 三分法：路径上的资源越界，不暴露存在性
            throw BizException.notFound(userId);
        }
        return user;
    }

    /** {@code 10012}：不允许对当前登录账号执行删除 / 停用（PRD F1-3 规则 9）。 */
    private void assertNotSelf(Long userId) {
        Long me = TenantHelper.getUserId();
        if (me != null && me.equals(userId)) {
            throw new BizException(ErrorCode.OPERATION_ON_SELF_FORBIDDEN);
        }
    }

    /**
     * {@code 10012} 的第三种形态：<b>降权自己</b>（§2.3 错误码表「如移除自己的管理员角色」）。
     *
     * <p>判据是「新 {@code roleIds} 不再包含我当前持有的某个角色」——
     * 而不是"新集合更小"：把 {@code org_admin} 换成一个自建的低权角色，
     * 集合大小不变但权限没了。
     *
     * <p><b>只在改自己时判</b>：改别人的角色本就是本接口的用途。
     */
    private void assertNotDemotingSelf(Long userId, List<Long> newRoleIds) {
        Long me = TenantHelper.getUserId();
        if (me == null || !me.equals(userId)) {
            return;
        }
        Set<Long> target = new HashSet<>(newRoleIds == null ? List.of() : newRoleIds);
        for (Long current : sysUserRoleMapper.selectRoleIdsByUser(userId)) {
            if (!target.contains(current)) {
                throw new BizException(ErrorCode.OPERATION_ON_SELF_FORBIDDEN);
            }
        }
    }

    /**
     * {@code roleIds} 必须全部是当前会话可见的角色。
     *
     * <p>可见性由租户插件决定（org_admin 看得到「本租户 + 平台预置」），
     * 所以这一条查询同时完成了「角色存在」与「不是别的租户的自建角色」两件事。
     * 不合规返回 400 —— §2.2 参数表「角色的 userType 适用性由服务端校验」。
     */
    private void assertRolesVisible(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        Set<Long> distinct = new LinkedHashSet<>(roleIds);
        long visible = sysUserRoleMapper.countVisibleRoles(List.copyOf(distinct));
        if (visible != distinct.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "roleIds 含不存在或不可见的角色");
        }
    }

    /**
     * {@code parentNodeId} 必须在当前登录人子树内（含自身节点），否则 {@code 10107}。
     *
     * <p>§2.2 参数表逐字：「不传返回 400，越界返回 {@code 10107}」。
     * {@code 10107} 而不是 404，因为这是<b>请求体里显式指定的目标</b> ——
     * 契约 §2.4 三分法：「我选的目标」越界要明确提示"请重新选择"，而非静默 404。
     *
     * <p>超管跳过越界判定：他的 {@code node_id} 是平台根哨兵 0，
     * 而 {@code SubtreeScopeHelper.isInSubtree} 的判定对哨兵恒为 false（该类注释已记此坑）。
     */
    private SystemOrgNode requireParentInMyScope(Long parentNodeId) {
        SystemOrgNode parent = orgNodeMapper.selectById(parentNodeId);
        if (parent == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }
        if (!TenantHelper.isSuperAdminSession()) {
            subtreeScopeHelper.assertTargetInSubtree(currentNodeId(), parentNodeId);
        }
        return parent;
    }

    /**
     * 学生上限（§2.2「创建学生账号受租户 {@code max_student_count} 限制」→ {@code 10207}）。
     *
     * <p>口径定案与「本接口实际不可达」的说明逐条见 {@code StudentQuotaMapper} 的类注释：
     * 本路径不写 {@code org_student} 档案，建出的学生不是"在读"，因此本校验恒不触发。
     * 保留它是因为 §2.2 的错误码表里有这一条，且它是 F-22 定案后唯一不需要重新发明的口径。
     *
     * <p>{@code runWithTenant} 不可省：超管会话下插件整体放行，
     * 不包住就会数出<b>全平台</b>的在读学生数（契约 §2.8 规则 1「从数据显式取」）。
     */
    private void assertStudentQuota(Integer userType, Long tenantId) {
        if (userType == null || userType != SysUser.USER_TYPE_STUDENT || tenantId == null
                || tenantId == SystemOrgNode.PLATFORM_ROOT_ID) {
            return;
        }
        Integer max = studentQuotaMapper.selectMaxStudentCount(tenantId);
        if (max == null || max <= 0) {
            return;
        }
        long current = TenantHelper.runWithTenant(tenantId, studentQuotaMapper::countActiveStudents);
        if (current + 1 > max) {
            throw new BizException(ErrorCode.STUDENT_COUNT_EXCEEDS_LIMIT);
        }
    }

    /**
     * 口令格式：8~20 位且<b>同时含字母与数字</b>（§2.2 / §2.5 参数表）。不合规 400。
     *
     * <p>与 {@code auth} 的 {@code PasswordService.assertStrongEnough} <b>刻意不共用</b>：
     * 那个方法带一条「不得与原密码相同」，那是 §1.6 <b>本人改密</b>的语义 ——
     * 管理员重置密码时不知道对方原密码，也不该知道。共用会让 §1.6 的规则漏进这里。
     * {@code common/account/PasswordHasher} 因此只暴露 {@code hash}，不暴露强度校验。
     */
    private void assertPasswordFormat(String password) {
        if (password == null || password.length() < 8 || password.length() > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码长度须为 8~20 位");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码须同时包含字母与数字");
        }
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 全量覆盖用户的角色绑定（§2.2 / §2.3：先删后插）。
     *
     * <p>{@code uk_user_role(user_id, role_id, deleted_at)} 末尾的 {@code deleted_at}
     * 让"解绑再重绑"不撞唯一键 —— 与 {@code SysRoleService#replaceRoleMenus} 同一条道理。
     *
     * <p><b>{@code tenant_id} 显式写</b>：超管会话下插件不注入，而这一行必须跟着
     * 被操作账号的租户走，不是跟着超管的会话（那是 0）。
     */
    private void replaceUserRoles(Long userId, Long tenantId, List<Long> roleIds) {
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId));
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        for (Long roleId : new LinkedHashSet<>(roleIds)) {
            SysUserRole row = new SysUserRole(userId, roleId);
            row.setTenantId(tenantId);
            sysUserRoleMapper.insert(row);
        }
    }

    private List<UserListVO> toListVOs(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        // 面包屑：一次性收集本页全部节点与其祖先，两次查询拼完，避免 N+1
        Map<Long, List<Long>> pathIdsByNode = new LinkedHashMap<>();
        Set<Long> allNodeIds = new LinkedHashSet<>();
        for (SysUser user : users) {
            List<Long> ids = nodePathIds(user.getNodeId());
            pathIdsByNode.put(user.getNodeId(), ids);
            allNodeIds.addAll(ids);
        }
        Map<Long, String> nameById = loadNodeNames(allNodeIds);
        Map<Long, Integer> nodeTypeById = loadNodeTypes(users);

        List<UserListVO> list = new ArrayList<>(users.size());
        for (SysUser user : users) {
            UserListVO vo = new UserListVO();
            vo.setId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setRealName(user.getRealName());
            vo.setUserType(user.getUserType());
            vo.setPhone(user.getPhone());
            vo.setAvatar(user.getAvatar());
            vo.setStatus(user.getStatus());
            vo.setNodeId(user.getNodeId());
            vo.setNodeType(nodeTypeById.get(user.getNodeId()));
            vo.setNodePath(joinNames(pathIdsByNode.get(user.getNodeId()), nameById));
            vo.setLastLoginTime(user.getLastLoginTime());
            vo.setCreateTime(user.getCreateTime());
            vo.setRemark(user.getRemark());
            list.add(vo);
        }
        return list;
    }

    /** 单个节点的面包屑（§2.2 响应的 {@code nodePath}）。 */
    private String resolveNodePath(Long nodeId) {
        List<Long> ids = nodePathIds(nodeId);
        return joinNames(ids, loadNodeNames(new LinkedHashSet<>(ids)));
    }

    /**
     * 自机构根节点起、至本节点的 ID 序列。
     *
     * <p>{@code NodePath.parseAncestorIds} <b>已跳过首位哨兵 0</b> ——
     * 平台根不属于任何租户，出现在租户的面包屑里反而是越界（契约 §2.9）。
     */
    private List<Long> nodePathIds(Long nodeId) {
        if (nodeId == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(
                NodePath.parseAncestorIds(nodeAncestorCache.get(nodeId)));
        ids.add(nodeId);
        return ids;
    }

    private Map<Long, String> loadNodeNames(Set<Long> nodeIds) {
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nameById = new LinkedHashMap<>();
        for (SystemOrgNodeMapper.NodeNameRow row : orgNodeMapper.selectNodeNames(List.copyOf(nodeIds))) {
            nameById.put(row.getId(), row.getNodeName());
        }
        return nameById;
    }

    private Map<Long, Integer> loadNodeTypes(List<SysUser> users) {
        Set<Long> nodeIds = new LinkedHashSet<>();
        users.forEach(u -> {
            if (u.getNodeId() != null) {
                nodeIds.add(u.getNodeId());
            }
        });
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> typeById = new LinkedHashMap<>();
        for (SystemOrgNode node : orgNodeMapper.selectBatchIds(nodeIds)) {
            typeById.put(node.getId(), node.getNodeType());
        }
        return typeById;
    }

    /** 按 ids 的顺序拼名称 —— {@code IN} 查询不保证顺序，而面包屑的顺序就是它的全部意义。 */
    private static String joinNames(List<Long> ids, Map<Long, String> nameById) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            String name = nameById.get(id);
            if (name == null || name.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /**
     * §2.5 服务端随机口令：≥12 位，含大小写字母 + 数字 + 符号。
     *
     * <p><b>禁止任何固定默认密码</b>（§2.5 参数表）：固定常量会出现在文档与工单中，
     * 攻击者拿到用户名列表即可批量撞库命中所有「已重置未改密」的账号。
     * 字符集去掉了 {@code 0/O/1/l/I} 一类形近字符 —— 这个口令是要被人念给用户听的。
     */
    private String generateStrongPassword() {
        String all = UPPER + LOWER + DIGIT + SYMBOL;
        List<Character> chars = new ArrayList<>(GENERATED_PASSWORD_LENGTH);
        // 四类各先保证一个，剩下的随机取 —— 否则纯随机有概率生成不含数字的串，
        // 而那个串会过不了本系统自己的 8~20 位含字母数字校验
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGIT));
        chars.add(pick(SYMBOL));
        while (chars.size() < GENERATED_PASSWORD_LENGTH) {
            chars.add(pick(all));
        }
        // 洗牌，免得前四位的类别恒定
        for (int i = chars.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Character tmp = chars.get(i);
            chars.set(i, chars.get(j));
            chars.set(j, tmp);
        }
        StringBuilder sb = new StringBuilder(chars.size());
        chars.forEach(sb::append);
        return sb.toString();
    }

    private char pick(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }

    /**
     * 当前登录人的 {@code node_id}（数据权限的唯一锚点，契约 §2.4）。
     *
     * <p>显式取一次而不是用 {@code SubtreeScopeHelper} 的无参重载：越界判定要同时知道
     * "我"和"目标"，得把它传给带参重载。
     *
     * <p><b>为什么按 {@code userId} 回查一次库，而不是注入 {@code CurrentContextProvider}</b>：
     * 那个 SPI 在容器里可能有<b>不止一个</b>实现（模块 01 的「无会话」默认实现、
     * 模块 02 的 Sa-Token 实现，以及测试用的 {@code @Primary} 实现），谁被注进来取决于
     * 装配顺序；而 {@code TenantHelper} 是文档指定的<b>租户上下文唯一入口</b>，
     * 它持有的是那个静态门面，全系统只有一份、也是 {@code TenantConfig} 唯一初始化的那份。
     * 靠 {@code TenantHelper.getUserId()} 定位"我是谁"，再点查 {@code node_id}，
     * 两步都不依赖"注进来的是哪一个 Bean"。
     *
     * <p>代价是一次主键点查（命中 {@code PRIMARY}），只发生在 §2.1 列表与 §2.2 建号
     * 两条非高频路径上，且<b>超管那一支根本不走这里</b>。
     * 换来的是行为与装配无关 —— 而装配相关的错误表现为"数据权限静默失效"，
     * 那是 {@code SubtreeScopeHelper} 类注释里要求记 ERROR 的那一类。
     */
    private Long currentNodeId() {
        Long userId = TenantHelper.getUserId();
        return userId == null ? null : sysUserMapper.selectNodeIdById(userId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
