package com.edumatrix.system.user.service;

import org.springframework.stereotype.Service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodeAncestorCache;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.system.user.entity.SystemOrgNode;
import com.edumatrix.system.user.entity.SystemOrgNodeChangeLog;
import com.edumatrix.system.user.mapper.SystemOrgNodeChangeLogMapper;
import com.edumatrix.system.user.mapper.SystemOrgNodeMapper;

/**
 * 03-01 §2.2 / §2.4 的组织树副作用：<b>建号即建节点、删号即删节点</b>。
 *
 * <h2>⚠ 临时构件：{@code org} 领域建成<b>建人/删人接口</b>后本类应删除（模块 07）</h2>
 * <p>取舍理由与交接方式逐条见 {@link SystemOrgNode} 的类注释。一句话：
 * {@code org} 领域要到模块 06/07 才存在，而检查③禁止领域包互相 import，
 * 现在替模块 06 写一个通用的节点服务等于<b>在没有工单的情况下替它做设计决策</b>。
 *
 * <p><b>模块 06 已落地，本类原样保留</b> —— 它的六个接口是查询/详情/修改/移动/停用/
 * 重置密码，<b>没有建节点与删节点</b>（03-02 §2.2：「建节点 = 建人，一律走接口 8/12/17」）。
 * 退休时机是<b>模块 07</b>，届时 {@code system/user} 改调对方 Service；
 * 检查③仍禁止直接 import 实体，故对方 Service 要返回自己的 DTO，
 * 而 {@link #createTenantRootNode} 的两条例外必须<b>原样保留</b>。
 *
 * <p><b>{@link #assertParentAcceptsChild} 现在有一份同源的第二实现</b>：
 * 模块 06 的 {@code org/node/service/NodeTypeRule}（02-数据库设计 §3.1.5 要求「所有入口共用」
 * 的那个静态方法）。检查③挡着，两份在模块 07 之前必须并存 ——
 * <b>改任一份都要同时改另一份，且不会有任何东西报错</b>。
 *
 * <h2>最小集边界 —— 多一点都不做</h2>
 * <table border="1">
 *   <caption>本类做什么、不做什么</caption>
 *   <tr><th>做</th><th>不做（归模块 06/07）</th></tr>
 *   <tr>
 *     <td>
 *       父子类型校验（{@code 10104}/{@code 10105}/{@code 10106}）<br>
 *       {@code ancestors} 由父节点推导<br>
 *       深度 ≤ 50 级校验（契约 §2.3 约束 5）<br>
 *       {@code ref_user_id} ↔ {@code node_id} 双向回填<br>
 *       父节点 {@code child_count} ± 1<br>
 *       建档轨迹 {@code change_type = 1}<br>
 *       <b>机构根节点</b>（{@link #createTenantRootNode}，模块 04 的 03-01 §5.3 步骤②）
 *     </td>
 *     <td>
 *       节点移动与 {@code ancestors} 递归重算<br>
 *       子树行锁<br>
 *       {@code student_count} 维护（见下）<br>
 *       {@code org_teacher} / {@code org_student} 档案<br>
 *       节点停用/启用与冻结集读写<br>
 *       其余 7 种 {@code change_type}
 *     </td>
 *   </tr>
 * </table>
 *
 * <h2>为什么不维护 {@code org_node.student_count}</h2>
 * <p>DDL 给它的语义是「子树内<b>在读</b>学生节点总数」，而「在读」的定义是
 * {@code org_student.status = 0} —— 本路径<b>不写 {@code org_student} 档案</b>，
 * 经它建出的学生因此不是"在读"，加进计数反而会让这个冗余列与它自己的定义不符。
 * 与 {@code StudentQuotaMapper} 的口径定案（按 {@code org_student} 在读行数计）自洽：
 * 两处必须同口径，否则同一个租户会算出两个学生数。
 *
 * <p>{@code child_count} 则照常维护 —— 它是<b>纯结构计数</b>，语义里没有"在读"这类条件，
 * 无歧义。
 */
@Service
public class PlatformNodeWriter {

    private final SystemOrgNodeMapper nodeMapper;
    private final SystemOrgNodeChangeLogMapper changeLogMapper;
    private final NodeAncestorCache nodeAncestorCache;

    public PlatformNodeWriter(SystemOrgNodeMapper nodeMapper,
                              SystemOrgNodeChangeLogMapper changeLogMapper,
                              NodeAncestorCache nodeAncestorCache) {
        this.nodeMapper = nodeMapper;
        this.changeLogMapper = changeLogMapper;
        this.nodeAncestorCache = nodeAncestorCache;
    }

    // =====================================================================
    // §2.2 建号：同事务内建节点 + 写建档轨迹
    // =====================================================================

    /**
     * 在 {@code parentNodeId} 下创建节点，并写一条 {@code change_type = 1} 的建档轨迹。
     *
     * <p><b>调用方必须已在事务内</b>（{@code SysUserService#create} 上有
     * {@code @Transactional}）—— §2.2 的副作用逐字是「<b>同一事务内</b>创建
     * {@code org_node} 节点并回写 {@code sys_user.node_id}」。
     *
     * @param userId       新账号 id（成为节点的 {@code ref_user_id}）
     * @param userType     {@code sys_user.user_type}；<b>新节点的 {@code node_type} 与它恒等</b>
     * @param nodeName     节点名，取账号的 {@code realName}
     * @param parentNodeId 父节点；超管建平台级账号时传 {@code 0}
     * @return 新节点 id，供调用方回写 {@code sys_user.node_id}
     */
    public Long createNode(Long userId, Integer userType, String nodeName, Long parentNodeId) {
        SystemOrgNode parent = requireParent(parentNodeId);
        assertParentAcceptsChild(parent, userType);
        assertDepthWithinLimit(parent);

        SystemOrgNode node = new SystemOrgNode();
        node.setParentId(parentNodeId);
        node.setAncestors(parent.selfPrefix());
        node.setNodeName(nodeName);
        // 契约 §5：node_type 与 user_type 取值完全一致，【不做任何映射】。
        // §2.2 参数表对此有一整段警告：「userType 加一」是一次已完成的节点类型重编号
        // 留下的残留写法，按它实现会把教师建成学生类型的节点，导致该教师永远分配不到
        // 学员（10106）、stat_*.teacher_node_id 恒为 NULL、导师看板恒空
        node.setNodeType(userType);
        node.setRefUserId(userId);
        node.setSort(0);
        // org_node.status 是停用的唯一权威（契约 §2.3），本模块只写默认值 0、此后不改
        node.setStatus(0);
        node.setChildCount(0);
        // 【必须显式写 tenant_id，且必须取自父节点】
        //   ① org_node.tenant_id 是 BIGINT NOT NULL【无默认值】（与 sys_user.tenant_id
        //      DEFAULT 0 不同），插件不注入就直接报 Field 'tenant_id' doesn't have a default value；
        //   ② 而超管会话下租户插件走的是「整体放行」通道（ignoreTable 返回 true），
        //      INSERT 时确实不注入租户列；
        //   ③ 取自父节点而不是会话 —— 契约 §2.8 规则 1「从数据显式取」。
        //      超管的会话租户是 0，沿用它会把这个节点建到平台租户下，
        //      于是该账号在自己机构的组织树里【永远查不到】，而且不报错
        node.setTenantId(parent.getTenantId());
        nodeMapper.insert(node);

        nodeMapper.addChildCount(parentNodeId, 1);

        SystemOrgNodeChangeLog changeLog = new SystemOrgNodeChangeLog();
        changeLog.setNodeId(node.getId());
        changeLog.setChangeType(SystemOrgNodeChangeLog.CHANGE_TYPE_CREATE);
        // DDL 注释：change_type=1 建档时 from_parent_id 为 NULL
        changeLog.setFromParentId(null);
        changeLog.setToParentId(parentNodeId);
        changeLog.setOperatorId(TenantHelper.getUserId());
        // org_node_change_log.tenant_id 同样是 NOT NULL 无默认值，理由同上
        changeLog.setTenantId(parent.getTenantId());
        changeLogMapper.insert(changeLog);

        return node.getId();
    }

    // =====================================================================
    // 03-01 §5.3 步骤②：机构根节点 —— 模块 04 开通租户时唯一的建节点入口
    // =====================================================================

    /**
     * 建<b>机构根节点</b>：它的 {@code id}【就是】租户 {@code id}，且它<b>就是</b>机构最高管理员本人
     * （契约 §2.1、03-01 §5.0 / §5.3 步骤②）。
     *
     * <h2>为什么不能复用 {@link #createNode}，也不能给它加一个"可选指定 id"的重载</h2>
     * <p>对不上的<b>不是一处而是两处</b>：
     * <ol>
     *   <li>{@code id} —— {@code createNode} 走 {@code ASSIGN_ID} 自己生成，而这里必须等于租户 id
     *       （DDL 对 {@code sys_tenant.id} 的注释逐字：「其值 = 该机构在 {@code org_node} 上的
     *       根节点 id」）。不等的后果<b>不报错</b>：树能建出来、账号能登录，只是
     *       {@code sys_tenant.root_node_id} 指向一个与 {@code tenant_id} 不同的节点，
     *       此后所有按 {@code tenant_id} 过滤的查询都对不上；
     *   <li>{@code tenant_id} —— {@code createNode} 取自<b>父节点</b>（契约 §2.8 规则 1
     *       「从数据显式取」），而这里的父是<b>平台根</b>，其 {@code tenant_id = 0}：
     *       照抄会把整个机构建到平台租户下，同样不报错。
     * </ol>
     * <p>两个参数一起放开，{@code createNode} 就从「在父节点下建人」变成「你说建什么我就建什么」，
     * <b>父节点推导 {@code tenant_id} 这条护栏会从必然降级成可选</b>——而 03-01 §2.2 那条路径
     * 正靠它兜着。所以本方法是<b>另开一个窄口</b>：两个"例外"都写死在方法内部、只此一处，
     * 调用方无从传错。
     *
     * <h2>为什么不在 {@code system/tenant} 自己写一段</h2>
     * <p>那会多出一个<b>不在 {@link SystemOrgNode} 交接清单上的临时构件</b>，
     * 模块 06 删完清单上的五个就会以为交接完了；而它含 {@code ancestors} 推导、
     * {@code child_count} 维护、建档轨迹三段与本类重复的逻辑，规则一改两份不会同步。
     * 05-工程结构.md §D 模块 04 那一行的「同时触及」列写的就是 {@code system/user/}。
     *
     * <p><b>调用方必须已在事务内</b>：§5.3 的三步「任一步失败整体回滚」。
     *
     * @param tenantId    步骤①刚插入的租户 id；<b>同时是本节点的 id 与 tenant_id</b>
     * @param adminUserId 机构最高管理员账号 id（成为 {@code ref_user_id}）
     * @param orgName     节点名，取<b>机构名称</b>（§5.3 步骤②表、响应示例的 {@code adminNodePath}、
     *                    §5.4「改机构名同步更新根节点 {@code node_name}」三处一致）
     */
    public void createTenantRootNode(Long tenantId, Long adminUserId, String orgName) {
        // 父恒为平台根（契约 §2.1：机构根节点 parent_id = 0、ancestors = "0"）。
        // 仍然查一次并走同一套父子类型校验：平台根只挂 node_type = 1，本节点正是 1
        SystemOrgNode platformRoot = requireParent(SystemOrgNode.PLATFORM_ROOT_ID);
        assertParentAcceptsChild(platformRoot, NodePath.NODE_TYPE_ADMIN);

        SystemOrgNode node = new SystemOrgNode();
        // 【例外①】id 不由 ASSIGN_ID 生成 —— MyBatis-Plus 只在 id 为 null 时才生成，
        // 显式设值即按设的写。这是全系统仅有的两个节点 id 特例之一（另一个是平台根 id = 0）
        node.setId(tenantId);
        node.setParentId(SystemOrgNode.PLATFORM_ROOT_ID);
        node.setAncestors(platformRoot.selfPrefix());
        node.setNodeName(orgName);
        node.setNodeType(NodePath.NODE_TYPE_ADMIN);
        node.setRefUserId(adminUserId);
        node.setSort(0);
        node.setStatus(0);
        node.setChildCount(0);
        // 【例外②】tenant_id 取新租户 id，【不是】父节点的 0 —— 与 createNode 的唯一语义分叉。
        // 沿用父节点会把整个机构建到平台租户下，而且不报错（契约 §2.1：其 tenant_id 即节点自身 id）
        node.setTenantId(tenantId);
        nodeMapper.insert(node);

        nodeMapper.addChildCount(SystemOrgNode.PLATFORM_ROOT_ID, 1);

        SystemOrgNodeChangeLog changeLog = new SystemOrgNodeChangeLog();
        changeLog.setNodeId(tenantId);
        changeLog.setChangeType(SystemOrgNodeChangeLog.CHANGE_TYPE_CREATE);
        changeLog.setFromParentId(null);
        changeLog.setToParentId(SystemOrgNode.PLATFORM_ROOT_ID);
        changeLog.setOperatorId(TenantHelper.getUserId());
        changeLog.setTenantId(tenantId);
        changeLogMapper.insert(changeLog);
    }

    // =====================================================================
    // §2.4 删号：逻辑删除节点
    // =====================================================================

    /**
     * 逻辑删除该账号的节点。<b>其下若仍有未删除的子节点则拒绝（{@code 10108}）。</b>
     *
     * <p>§2.4 数据权限原文：「同时逻辑删除其 {@code org_node} 节点……该节点下若仍有
     * 未删除的子节点则拒绝删除（{@code 10108}），须先迁移子树」。
     *
     * <p><b>不写异动轨迹</b>：§2.4 的原文只有节点逻辑删除与作废 Token 两件事，
     * 03-02 的三个删除接口（接口 10/14/19）同样不写。补一条是发明规则。
     *
     * @param nodeId 待删节点；{@code null} 时静默返回（平台级账号可能没有节点）
     */
    public void deleteNode(Long nodeId) {
        if (nodeId == null) {
            return;
        }
        SystemOrgNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            // 节点已被删除：删除接口是幂等的（§2.4「对已删除用户重复调用同样返回 200」）
            return;
        }
        if (nodeMapper.countLiveChildren(nodeId) > 0) {
            throw new BizException(ErrorCode.NODE_HAS_CHILDREN);
        }
        nodeMapper.deleteById(nodeId);
        nodeMapper.addChildCount(node.getParentId(), -1);
        // 祖先链缓存随之失效。本节点没有子树（刚校验过 10108），故 evict 单个即可 ——
        // 用 evictSubtree 会在节点已删除的情况下走 selectPath 拿 null 再退化成 evict(nodeId)，
        // 多一次查询、结果相同
        nodeAncestorCache.evict(nodeId);
    }

    // =====================================================================
    // 校验（契约 §2.3 结构约束）
    // =====================================================================

    private SystemOrgNode requireParent(Long parentNodeId) {
        if (parentNodeId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "parentNodeId 不能为空");
        }
        SystemOrgNode parent = nodeMapper.selectById(parentNodeId);
        if (parent == null) {
            // 不存在、已删除、或跨租户被插件过滤掉 —— 三者一律 10101，不区分（不暴露存在性）
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }
        return parent;
    }

    /**
     * 父子类型合法性（契约 §2.3 承载规则表，§2.2 错误码表逐码对应）。
     *
     * <pre>
     * node_type=0 平台超管 → 只挂 1
     * node_type=1 管理员   → 可挂 1 / 2 / 3
     * node_type=2 教师     → 【仅】3      违规 → 10105
     * node_type=3 学生     → 【叶子】     违规 → 10106
     * 其余不合法组合                      → 10104
     * </pre>
     *
     * <p><b>三个码的分工照分册，不合并</b>：{@code 10105} / {@code 10106} 是
     * 教师/学生两条最常撞的规则，前端据它们给出的提示语不同
     * （"教师名下只能加学员" vs "学员不能再带人"）；{@code 10104} 是兜底。
     */
    private void assertParentAcceptsChild(SystemOrgNode parent, Integer childType) {
        Integer parentType = parent.getNodeType();
        if (parentType == null || childType == null) {
            throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
        }
        switch (parentType) {
            case NodePath.NODE_TYPE_PLATFORM -> {
                // 平台根下只挂管理员（契约 §2.3：超管节点下只能挂管理员）
                if (childType != NodePath.NODE_TYPE_ADMIN) {
                    throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
                }
            }
            case NodePath.NODE_TYPE_ADMIN -> {
                // 管理员可挂 1/2/3，不可挂 0（平台根全表唯一一行，不可能有第二个）
                if (childType == NodePath.NODE_TYPE_PLATFORM) {
                    throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
                }
            }
            case NodePath.NODE_TYPE_TEACHER -> {
                if (childType != NodePath.NODE_TYPE_STUDENT) {
                    throw new BizException(ErrorCode.TEACHER_NODE_ONLY_ACCEPTS_STUDENT);
                }
            }
            case NodePath.NODE_TYPE_STUDENT ->
                    throw new BizException(ErrorCode.STUDENT_NODE_MUST_BE_LEAF);
            default -> throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
        }
    }

    /**
     * 树深度上限 50 级（契约 §2.3 约束 5），超限 <b>400</b>。
     *
     * <p><b>必须在服务层校验</b>，原文：不校验的话第 51 级会在写入时
     * {@code Data too long} 让整个事务回滚，而那种失败点难以定位。
     * 新节点的深度 = 父节点深度 + 1。
     */
    private void assertDepthWithinLimit(SystemOrgNode parent) {
        if (parent.depth() + 1 > SystemOrgNode.MAX_DEPTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "组织树深度已达上限 " + SystemOrgNode.MAX_DEPTH + " 级（契约 §2.3 约束 5）");
        }
    }
}
