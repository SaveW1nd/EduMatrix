package com.edumatrix.org.member.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.edumatrix.common.account.PasswordHasher;
import com.edumatrix.common.account.SessionRevoker;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.id.IdWorker;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodeAncestorCache;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.entity.OrgNodeChangeLog;
import com.edumatrix.org.node.mapper.NodeAccountMapper;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.service.CurrentNodeResolver;
import com.edumatrix.org.node.service.NodeChangeLogWriter;
import com.edumatrix.org.node.service.NodeTypeRule;

/**
 * 建人 / 删人的<b>公共骨架</b>：三个建人接口（8 / 12 / 17）与三个删人接口（10 / 14 / 19）共用。
 *
 * <h2>「三写一事务」是本类的全部意义</h2>
 * <p>PRD F1-3 规则 1：{@code org_node} + {@code sys_user} + 档案表<b>任一失败整体回滚</b>，
 * 不允许出现「有账号无节点」或「有节点无账号」。落地方式是：调用方 Service 上有
 * {@code @Transactional}，本类的方法全部跑在那个事务里，<b>自己不开事务</b>
 * （开了就是嵌套 {@code REQUIRED}，语义相同但会让「事务边界在哪」多一个答案）。
 *
 * <p><b>校验一律在任何写入之前做完。</b>PRD F1-3 的自检项逐字：「未勾选监护人同意时创建学生
 * 返回 {@code 400} 且<b>无任何节点/账号/档案产生</b>」——那验的正是这一点。
 * 参数校验由 DTO 注解在进 Service 之前完成，业务校验（唯一性、子树、父子类型）在本类的
 * {@link #createPerson} 里、写第一行之前完成。
 *
 * <h2>为什么不复用 {@code system/user/service/PlatformNodeWriter}</h2>
 * <p>{@code check_backend_conventions.sh} 检查③ 禁止 {@code org} import {@code system}
 * （反向同样禁止）。而 {@code org_node} 本来就是 {@code org} 自己的表，模块 07 的「涉及表」
 * 也把它列在<b>写</b>栏 —— 在本领域内直连它不是绕路，是正路。
 * {@code PlatformNodeWriter} 的退休另有安排，见 {@code com.edumatrix.org.node} 的
 * {@code package-info}。
 *
 * <h2>父子类型校验走 {@code NodeTypeRule}，不自己写第三份</h2>
 * <p>02-数据库设计 §3.1.5 把「<b>全部</b>会改变父子关系的入口」逐一列出并要求收敛到一个静态方法，
 * <b>新建人员正是其中一个</b>。全库已经有两份同源实现（{@code NodeTypeRule} 与
 * {@code PlatformNodeWriter#assertParentAcceptsChild}，后者因检查③ 暂时无法消除）——
 * <b>本模块绝不添第三份</b>。
 */
@Service
public class MemberWriteSupport {

    private static final Logger log = LoggerFactory.getLogger(MemberWriteSupport.class);

    private final OrgNodeMapper nodeMapper;
    private final NodeAccountMapper accountMapper;
    private final NodeChangeLogWriter changeLogWriter;
    private final SubtreeScopeHelper subtreeScopeHelper;
    private final CurrentNodeResolver currentNodeResolver;
    private final NodeAncestorCache nodeAncestorCache;
    private final PasswordHasher passwordHasher;
    private final SessionRevoker sessionRevoker;
    private final InitialPasswordFactory initialPasswordFactory;

    public MemberWriteSupport(OrgNodeMapper nodeMapper,
                              NodeAccountMapper accountMapper,
                              NodeChangeLogWriter changeLogWriter,
                              SubtreeScopeHelper subtreeScopeHelper,
                              CurrentNodeResolver currentNodeResolver,
                              NodeAncestorCache nodeAncestorCache,
                              PasswordHasher passwordHasher,
                              SessionRevoker sessionRevoker,
                              InitialPasswordFactory initialPasswordFactory) {
        this.nodeMapper = nodeMapper;
        this.accountMapper = accountMapper;
        this.changeLogWriter = changeLogWriter;
        this.subtreeScopeHelper = subtreeScopeHelper;
        this.currentNodeResolver = currentNodeResolver;
        this.nodeAncestorCache = nodeAncestorCache;
        this.passwordHasher = passwordHasher;
        this.sessionRevoker = sessionRevoker;
        this.initialPasswordFactory = initialPasswordFactory;
    }

    // =====================================================================
    // 建人
    // =====================================================================

    /**
     * 建 {@code sys_user} + {@code org_node} + 绑角色 + 建档轨迹。档案表由调用方在同事务内写。
     *
     * <p><b>顺序不可调换</b>：账号 → 节点 → 回写 {@code node_id}。节点的 {@code ref_user_id}
     * 要指向账号，而账号的 {@code node_id} 是 {@code NOT NULL} —— 先插账号占位 0，
     * 建完节点立刻回写，两步同事务，中间态不可见。
     */
    public PersonCreated createPerson(PersonCreateCmd cmd) {
        OrgNode parent = requireParentInMyScope(cmd.parentNodeId());

        // -------- 写入之前把校验做完（自检项：失败时不得有任何行产生） --------
        NodeTypeRule.assertCanBeChildOf(parent.getNodeType(), cmd.userType());
        assertDepthWithinLimit(parent);
        if (parent.isDisabled()) {
            // §4.2 / §5.2 / §6.2 错误码表都列了 10109
            throw new BizException(ErrorCode.NODE_DISABLED);
        }
        String nodeName = cmd.nodeName() == null || cmd.nodeName().isBlank()
                ? cmd.realName() : cmd.nodeName();
        assertNodeNameUnique(cmd.parentNodeId(), nodeName, null);
        String username = cmd.username() == null || cmd.username().isBlank()
                ? cmd.phone() : cmd.username();
        assertUsernameFree(username, null);
        assertPhoneFree(cmd.phone(), null);

        String plainPassword = initialPasswordFactory.resolve(cmd.initPassword());
        Long operatorId = TenantHelper.getUserId();
        Long tenantId = parent.getTenantId();

        // -------- 1. sys_user --------
        Long userId = IdWorker.nextId();
        try {
            accountMapper.insertUser(userId, username, passwordHasher.hash(plainPassword),
                    cmd.userType(), cmd.realName(), cmd.phone(), tenantId, operatorId, cmd.remark());
        } catch (DuplicateKeyException e) {
            throw translateDuplicate(e);
        }

        // -------- 2. org_node --------
        Long nodeId = insertNode(parent, userId, cmd, nodeName, tenantId, operatorId);

        // -------- 3. 回写 sys_user.node_id --------
        accountMapper.updateNodeId(userId, nodeId);

        // -------- 4. 绑角色 --------
        bindRole(userId, cmd.roleKey(), tenantId, operatorId);

        // -------- 5. 父节点 child_count + 1 --------
        nodeMapper.addChildCount(cmd.parentNodeId(), 1);

        // -------- 6. 建档轨迹 change_type = 1 --------
        // DDL 注释：change_type=1 建档时 from_parent_id 为 NULL
        changeLogWriter.write(nodeId, OrgNodeChangeLog.CHANGE_TYPE_CREATE,
                null, cmd.parentNodeId(), null, tenantId);

        return new PersonCreated(userId, nodeId, parent.selfPrefix(), username, plainPassword);
    }

    private Long insertNode(OrgNode parent, Long userId, PersonCreateCmd cmd,
                            String nodeName, Long tenantId, Long operatorId) {
        OrgNode node = new OrgNode();
        node.setParentId(cmd.parentNodeId());
        node.setAncestors(parent.selfPrefix());
        node.setNodeName(nodeName);
        // 契约 §5：node_type 与 user_type 取值完全一致，【不做任何映射】。
        // §2.2 参数表对此有一整段警告：按「userType 加一」实现会把教师建成学生类型的节点，
        // 于是该教师永远分配不到学员（10106）、stat_*.teacher_node_id 恒为 NULL、导师看板恒空
        node.setNodeType(cmd.userType());
        node.setRefUserId(userId);
        node.setSort(cmd.sort() == null ? 0 : cmd.sort());
        // org_node.status 是停用的唯一权威（契约 §2.3），建人只写默认值 0；
        // 停用/启用一律走 03-02 接口 5，本模块此后不改它
        node.setStatus(0);
        node.setChildCount(0);
        node.setStudentCount(0);
        // 【必须显式写 tenant_id，且必须取自父节点】org_node.tenant_id 是 NOT NULL 无默认值，
        // 而超管会话下租户插件走「整体放行」通道、INSERT 时不注入租户列。
        // 取自父节点而不是会话 —— 契约 §2.8 规则 1「从数据显式取」
        node.setTenantId(tenantId);
        node.setRemark(cmd.remark());
        node.setCreateBy(operatorId);
        node.setUpdateBy(operatorId);
        nodeMapper.insert(node);
        return node.getId();
    }

    // =====================================================================
    // 删人
    // =====================================================================

    /**
     * 删人的公共部分：节点 → 账号 → 角色 → 作废 Token → 父节点 {@code child_count - 1}
     * → 清祖先链缓存。档案表由调用方在同事务内删。
     *
     * <p><b>子节点保护由调用方先做</b>：管理员是 {@code 10108}（§4.4），
     * 教师是 {@code 10206}（§5.4）—— <b>两个码不同，语义也不同</b>，
     * 合并成一个会让前端对教师说出「请先迁移子节点」这种看不懂的话。
     *
     * <p><b>写 {@code deleted_at} 而不是 {@code status}</b>，理由见
     * {@code NodeAccountMapper} 的类注释（三个删除接口各有一整段原文）。
     */
    public void deletePerson(OrgNode node) {
        Long operatorId = TenantHelper.getUserId();
        Long userId = node.getRefUserId();

        nodeMapper.deleteById(node.getId());
        nodeMapper.addChildCount(node.getParentId(), -1);

        if (userId != null) {
            accountMapper.softDeleteUser(userId, operatorId);
            accountMapper.softDeleteUserRoles(userId);
            // 【在事务内作废 Token】三个删除接口的原文都是「事务内：…→ 作废在线 Token → …」。
            // SessionRevoker 类注释：Redis 不参与回滚，事务内调用或提交后调用皆可，
            // 但【绝不可先返回成功再异步作废】—— 宁可多作废一瞬，不可漏放一瞬
            sessionRevoker.revokeAllSessions(userId);
        }

        // 本节点已确认没有子节点（调用方刚校验过 10108 / 10206），故 evict 单个即可
        nodeAncestorCache.evict(node.getId());
    }

    // =====================================================================
    // 校验
    // =====================================================================

    /**
     * 目标节点必须在当前登录人子树内（含自身），否则 {@code 10107}。
     *
     * <p>{@code 10107} 而不是 404：契约 §2.4 三分法 ——「<b>我选的目标</b>」越界要提示
     * 「请重新选择」，而不是静默 404。三个建人接口的数据权限栏用的都是这个码。
     */
    public OrgNode requireParentInMyScope(Long parentNodeId) {
        if (parentNodeId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "parentNodeId 不能为空");
        }
        OrgNode parent = nodeMapper.selectById(parentNodeId);
        if (parent == null) {
            // 不存在 / 已删除 / 跨租户被插件过滤掉 —— 三者一律 10101，不区分（不暴露存在性）
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }
        subtreeScopeHelper.assertTargetInSubtree(currentNodeResolver.requireCurrentNodeId(),
                parentNodeId);
        return parent;
    }

    /**
     * 路径 {@code {id}} 上的节点：不在子树内一律 <b>404</b>（契约 §2.4 三分法的另一半 ——
     * 「<b>我要操作的东西</b>」越界不暴露存在性）。
     */
    public OrgNode requireNodeInMyScope(Long nodeId, int expectedNodeType) {
        OrgNode node = nodeId == null ? null : nodeMapper.selectById(nodeId);
        if (node == null || node.getNodeType() == null || node.getNodeType() != expectedNodeType) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }
        subtreeScopeHelper.assertInSubtree(currentNodeResolver.requireCurrentNodeId(), nodeId);
        return node;
    }

    /** 同级下节点名唯一 → {@code 10102}（§4.2 / §5.2 错误码表）。 */
    public void assertNodeNameUnique(Long parentId, String nodeName, Long excludeNodeId) {
        if (nodeName == null || nodeName.isBlank()) {
            return;
        }
        if (nodeMapper.countSameNameSibling(parentId, nodeName, excludeNodeId) > 0) {
            throw new BizException(ErrorCode.NODE_NAME_DUPLICATED);
        }
    }

    /** 用户名唯一 → {@code 10001}。<b>索引是真相，本方法是提示</b>，见 Mapper 注释。 */
    public void assertUsernameFree(String username, Long excludeUserId) {
        if (username != null && accountMapper.countByUsername(username, excludeUserId) > 0) {
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
    }

    /** 手机号本租户内唯一 → {@code 10013}。 */
    public void assertPhoneFree(String phone, Long excludeUserId) {
        if (phone != null && accountMapper.countByPhone(phone, excludeUserId) > 0) {
            throw new BizException(ErrorCode.PHONE_ALREADY_USED);
        }
    }

    /** 不允许对当前登录账号执行 → {@code 10012}（§4.3 / §4.4 / §5.4）。 */
    public void assertNotSelf(Long nodeId) {
        if (nodeId != null && nodeId.equals(currentNodeResolver.currentNodeId())) {
            throw new BizException(ErrorCode.OPERATION_ON_SELF_FORBIDDEN);
        }
    }

    /**
     * 树深度上限 50 级（契约 §2.3 约束 5），超限 <b>400</b>。
     *
     * <p>必须在服务层校验：不校验的话第 51 级会在写入时 {@code Data too long}
     * 让整个三写事务回滚，而那种失败点难以定位。
     */
    private static void assertDepthWithinLimit(OrgNode parent) {
        if (parent.depth() + 1 > OrgNode.MAX_DEPTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "组织树深度已达上限 " + OrgNode.MAX_DEPTH + " 级（契约 §2.3 约束 5）");
        }
    }

    /**
     * 改账号那半边（接口 9 / 13 / 18 共用）。
     *
     * <p>{@code realName} 改动<b>同步 {@code org_node.node_name}</b> 的动作由调用方按各自
     * 分册决定 —— 三个接口的参数表不同（管理员/教师有独立的 {@code nodeName} 参数，
     * 学生没有），合并进来会让「学生改名要不要同步节点名」这件事失去出处。
     */
    public void updateAccount(Long userId, String realName, String phone, String username) {
        assertPhoneFree(phone, userId);
        assertUsernameFree(username, userId);
        try {
            accountMapper.updateAccount(userId, realName, phone, username, TenantHelper.getUserId());
        } catch (DuplicateKeyException e) {
            throw translateDuplicate(e);
        }
    }

    /** 改节点展示属性（{@code node_name} / {@code sort} / {@code remark}）。 */
    public void updateNodeProfile(OrgNode node, String nodeName, Integer sort, String remark) {
        OrgNode update = new OrgNode();
        update.setId(node.getId());
        if (nodeName != null && !nodeName.isBlank()) {
            assertNodeNameUnique(node.getParentId(), nodeName, node.getId());
            update.setNodeName(nodeName);
        }
        update.setSort(sort);
        update.setRemark(remark);
        update.setUpdateBy(TenantHelper.getUserId());
        nodeMapper.updateById(update);
        if (nodeName != null && !nodeName.isBlank()) {
            nodeAncestorCache.evict(node.getId());
        }
    }

    /**
     * 建人时的模板套用：<b>本模块不实现</b>，传了就留一条 WARN。
     *
     * <p>{@code templateId} 在 §4.2 / §5.2 / §6.2 的参数表里，但<b>套用动作不在模块 07 的工单里</b>：
     * 模块 07 的「涉及表」没有 {@code org_resource_grant} 与 {@code org_perm_template}，
     * 04-实施计划.md §A 的接口分配把权限模板九个接口（42~50）分给<b>模块 17</b>、
     * 授权引擎六个（37~41、51）分给<b>模块 11</b>；模块 07 那一行逐字写的是
     * 「建人走模块 07 的事务，<b>授权走模块 11 的引擎</b>」。
     *
     * <p><b>留 WARN 而不是静默</b>：调用方传了 {@code templateId} 却什么都没发生，必须有人看得见。
     * 这与模块 06 对 {@code revokeOutOfScopeGrants=true} 的处置是同一条先例。
     */
    public void warnTemplateNotApplied(Long templateId, Long nodeId) {
        if (templateId != null) {
            log.warn("templateId={} 已忽略：套用权限模板是模块 11/17 的交付物，"
                            + "本模块只建人不授权（04-实施计划.md §A 接口分配、模块 07「禁止事项」）。"
                            + "新节点 nodeId={}，可在模块 11 上线后经 03-02 接口 50 手动套用",
                    templateId, nodeId);
        }
    }

    /**
     * 两个唯一键都可能命中，按约束名区分（与 {@code SysUserService#insertUserOrThrowDuplicate}
     * 逐字同源）。
     *
     * <p><b>不预先查「这个用户名是不是被删过」</b>：{@code uk_username(username, deleted_at)}
     * 已经处理了 —— 逻辑删除写的是毫秒时间戳，同一 {@code username} 可容纳任意多条已删除行，
     * <b>同名重建自动放行</b>。这是 {@code deleted_at} 用时间戳而非 0/1 的直接收益。
     */
    private static BizException translateDuplicate(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        if (message != null && message.contains("uk_tenant_phone")) {
            return new BizException(ErrorCode.PHONE_ALREADY_USED);
        }
        return new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
    }

    private void bindRole(Long userId, String roleKey, Long tenantId, Long operatorId) {
        Long roleId = accountMapper.selectRoleIdByKey(roleKey);
        if (roleId == null) {
            // 四个内置角色由 Flyway 基线插入（tenant_id = 0，平台级行放行）。
            // 取不到说明基线数据缺失 —— 那会让新账号 perms 恒空、与「系统开箱即不可用」
            // 同形（F-1 那个故障），必须当场炸而不是建出一个登不进去的号
            throw new BizException(ErrorCode.BAD_REQUEST, "内置角色 " + roleKey + " 不存在，基线数据缺失");
        }
        accountMapper.insertUserRole(IdWorker.nextId(), userId, roleId, tenantId, operatorId);
    }

    /** 建人入参（三个建人接口的公共部分）。 */
    public record PersonCreateCmd(Long parentNodeId,
                                  Integer userType,
                                  String roleKey,
                                  String realName,
                                  String phone,
                                  String username,
                                  String nodeName,
                                  Integer sort,
                                  String initPassword,
                                  String remark) {
    }

    /** 建人产出。{@code plainPassword} <b>只在本次响应返回一次</b>，不落库。 */
    public record PersonCreated(Long userId,
                                Long nodeId,
                                String ancestors,
                                String username,
                                String plainPassword) {
    }

    /** 账号的用户名（响应回显与「username 是否等于手机号」的判定）。 */
    public String usernameOf(Long userId) {
        return userId == null ? null : accountMapper.selectUsername(userId);
    }

    /** 账号的手机号。 */
    public String phoneOf(Long userId) {
        NodeAccountMapper.UserBriefRow row = userId == null ? null : accountMapper.selectUserBrief(userId);
        return row == null ? null : row.getPhone();
    }

    /** 节点路径面包屑（{@code nodePath}），从租户根到本节点，{@code /} 分隔。 */
    public String nodePath(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        java.util.List<Long> ids = new java.util.ArrayList<>(
                NodePath.parseAncestorIds(nodeAncestorCache.get(nodeId)));
        ids.add(nodeId);
        if (ids.isEmpty()) {
            return null;
        }
        java.util.Map<Long, String> names = new java.util.LinkedHashMap<>();
        for (OrgNode n : nodeMapper.selectByIds(ids)) {
            names.put(n.getId(), n.getNodeName());
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            String name = names.get(id);
            if (name == null || name.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(name);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
