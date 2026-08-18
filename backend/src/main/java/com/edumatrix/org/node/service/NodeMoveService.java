package com.edumatrix.org.node.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.metrics.MetricsRegistry;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodeAncestorCache;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.entity.OrgNodeChangeLog;
import com.edumatrix.org.node.mapper.NodeGrantScopeMapper;
import com.edumatrix.org.node.mapper.NodeMemberMapper;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.vo.NodeMovedVO;
import com.edumatrix.org.node.vo.OutOfScopeGrantVO;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * <b>全系统唯一改变树结构的入口</b>（03-02 §3.4、04-实施计划.md 模块 06 规则 1）。
 *
 * <p>模块 07 的分配导师（接口 20）、批量分配（21）、转交管理员（22）、教师调岗
 * 全都是本类的<b>语义化封装</b>，<b>不得另写一套改父逻辑</b>（模块 07 规则 5）。
 * 契约 §9.2 铁律 1 同时禁止把「移动」与「{@code ancestors} 重算」拆成两个事务。
 *
 * <h2>7 步事务：逐条对应 02-数据库设计 §3.1.3</h2>
 * <ol>
 *   <li>按 id <b>升序</b> {@code FOR UPDATE} 加锁（{@code OrgNodeMapper#selectForUpdateOrderById}）；
 *   <li><b>锁内</b>按 03-02 §3.4 的 11 条顺序逐条校验，各自错误码不同；
 *   <li>Java 侧算 {@code oldP} / {@code newSelfAnc} / {@code newP}；
 *   <li>更新被移动节点自身的 {@code parent_id} 与 {@code ancestors}；
 *   <li><b>【核心】</b>一条前缀替换 UPDATE 重算整棵子树；
 *   <li>{@code child_count} ±1、两条祖先链的 {@code student_count} ±n
 *       （<b>外加 {@code org_teacher.student_count}，见步骤 6 的注释</b>）；
 *   <li>写 {@code org_node_change_log}。
 * </ol>
 * <p><b>任一步失败整体回滚。</b>
 *
 * <h2>事务<b>提交之后</b>做两件事，且只有这两件</h2>
 * <ol>
 *   <li>递归清除被移动子树的 {@code node:anc:*} 键 —— 契约 §2.4「学员被移走后，
 *       原上级<b>立即</b>失去访问权」这句承诺的<b>唯一落地机制</b>；
 *   <li>两个 Histogram 埋点（契约 §7.1）。放在提交后，回滚掉的移动就不会被计进去。
 * </ol>
 *
 * <h2>本接口对客户端<b>不可自动重试</b></h2>
 * <p>00-通用约定 §7.5：超时后节点可能已移动成功，盲目重试会把它再移到另一位置。
 * 客户端应改为重新拉取节点详情确认当前 {@code parentId}。
 */
@Service
public class NodeMoveService {

    private static final Logger log = LoggerFactory.getLogger(NodeMoveService.class);

    private final OrgNodeMapper nodeMapper;
    private final NodeMemberMapper memberMapper;
    private final NodeGrantScopeMapper grantScopeMapper;
    private final NodeChangeLogWriter changeLogWriter;
    private final SubtreeScopeHelper subtreeScopeHelper;
    private final NodeAncestorCache nodeAncestorCache;
    private final CurrentNodeResolver currentNodeResolver;
    private final MeterRegistry meterRegistry;

    public NodeMoveService(OrgNodeMapper nodeMapper,
                           NodeMemberMapper memberMapper,
                           NodeGrantScopeMapper grantScopeMapper,
                           NodeChangeLogWriter changeLogWriter,
                           SubtreeScopeHelper subtreeScopeHelper,
                           NodeAncestorCache nodeAncestorCache,
                           CurrentNodeResolver currentNodeResolver,
                           MeterRegistry meterRegistry) {
        this.nodeMapper = nodeMapper;
        this.memberMapper = memberMapper;
        this.grantScopeMapper = grantScopeMapper;
        this.changeLogWriter = changeLogWriter;
        this.subtreeScopeHelper = subtreeScopeHelper;
        this.nodeAncestorCache = nodeAncestorCache;
        this.currentNodeResolver = currentNodeResolver;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 移动节点。
     *
     * @param movingNodeId 被移动节点（03-02 §3.4 的路径 {@code {id}}）
     * @param toParentId   目标父节点
     * @param options      可选项；{@code null} 时按默认（无原因、不回收跨管辖授权）
     */
    @Transactional(rollbackFor = Exception.class)
    public NodeMovedVO move(Long movingNodeId, Long toParentId, NodeMoveOptions options) {
        NodeMoveOptions opts = options == null ? NodeMoveOptions.none() : options;
        if (movingNodeId == null || toParentId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "id 与 toParentId 均不能为空");
        }

        // =================================================================
        // 步骤 1：按 id 升序加锁（固定加锁顺序，防并发移动死锁）
        //
        // 【加锁集合覆盖本事务全部点写入的行，不只是「被移动节点 + 目标父」】
        // 理由见 lockIds 的注释：只锁那两行时，步骤 6 会去写【没被排序覆盖】的行
        //（旧父的 child_count、两条祖先链的 student_count），10 并发下实测死锁。
        // 04-实施计划.md §D 前置风险项 R2「撞车后的影响面」那一行逐字：「若 10 并发下出现死锁，说明 id 升序加锁
        // 没有覆盖全部加锁点——这属于铁律 1 未落地，不可上线」
        // =================================================================
        // 先无锁读一次，仅用于【算出该锁哪些行】
        OrgNode movingPreview = nodeMapper.selectById(movingNodeId);
        OrgNode targetPreview = nodeMapper.selectById(toParentId);
        if (movingPreview == null || targetPreview == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }

        List<OrgNode> locked = nodeMapper.selectForUpdateOrderById(
                lockIds(movingPreview, targetPreview));
        OrgNode moving = pick(locked, movingNodeId);
        OrgNode target = pick(locked, toParentId);

        // -------- 校验 1：两者都存在且未逻辑删除，否则 10101 --------
        // 不存在 / 已删除 / 跨租户被插件过滤掉，三种成因【不区分】——不暴露存在性。
        // 【一切校验只认 locked 里的行】：预读那两行是锁外快照，可能已经过时；
        // 它只负责回答「锁哪些」，绝不参与判定
        if (moving == null || target == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }

        // =================================================================
        // 步骤 2：前置校验。【全部在锁内】，顺序逐条照 03-02 §3.4 的校验顺序表
        // =================================================================
        Long myNodeId = currentNodeResolver.requireCurrentNodeId();

        // -------- 校验 2：被移动节点 ∈ 我的子树，且不是我自己、不是租户根节点 --------
        // 这条约束同时保证了「不能把自己或上级搬走」（§3.4 数据权限栏）
        subtreeScopeHelper.assertTargetInSubtree(myNodeId, movingNodeId);
        if (movingNodeId.equals(myNodeId) || moving.isTenantRoot()) {
            throw BizException.targetOutOfScope(movingNodeId, null);
        }

        // -------- 校验 3：目标父节点 ∈ 我的子树（跨子树搬运在此被拒） --------
        subtreeScopeHelper.assertTargetInSubtree(myNodeId, toParentId);

        // -------- 校验 4：防成环 --------
        assertNoCycle(moving, target);

        // -------- 校验 5/6/7：承载规则（10105 / 10106 / 10104） --------
        NodeTypeRule.assertCanBeChildOf(target.getNodeType(), moving.getNodeType());

        // -------- 校验 8：目标父节点未停用 --------
        if (target.isDisabled()) {
            throw new BizException(ErrorCode.NODE_DISABLED);
        }

        // -------- 校验 9：目标父节点下无同名节点 --------
        if (nodeMapper.countSameNameSibling(toParentId, moving.getNodeName(), movingNodeId) > 0) {
            throw new BizException(ErrorCode.NODE_NAME_DUPLICATED);
        }

        // -------- 校验 10：被移动学生节点学籍状态为 0 在读 --------
        assertStudentActive(moving);

        // -------- 校验 11：目标父节点不等于当前上级（无变化的空操作） --------
        if (toParentId.equals(moving.getParentId())) {
            throw new BizException(ErrorCode.TARGET_PARENT_UNCHANGED);
        }

        // -------- 深度上限 50 级（契约 §2.3 约束 5），超限 400 --------
        // 不在 §3.4 的 11 条里：那 11 条都是业务码，这一条是结构性上限，返回 400。
        // 放在最后是为了让业务语义的拒绝先说话——一次「目标已停用」的移动，
        // 用户要看到的是 10109，而不是一句深度超限
        assertDepthWithinLimit(moving, target);

        // =================================================================
        // 步骤 3：算新旧前缀（Java 侧算好后作为参数传入）
        // =================================================================
        String oldPrefix = moving.selfPrefix();              // oldP：子树各行现有 ancestors 的公共前缀
        String newSelfAncestors = target.selfPrefix();       // 被移动节点自身的新 ancestors
        String newPrefix = newSelfAncestors + "," + movingNodeId;  // newP

        Long operatorId = TenantHelper.getUserId();
        Long oldParentId = moving.getParentId();

        // 迁移的在读学生数：必须在【重算之前】用旧前缀数，否则子树已经不在 oldP 之下了
        int movedStudentCount = (int) memberMapper.countActiveStudentsInSubtree(movingNodeId, oldPrefix);

        // =================================================================
        // 步骤 4：更新被移动节点自身
        // =================================================================
        nodeMapper.updateSelfOnMove(movingNodeId, toParentId, newSelfAncestors, operatorId);

        // =================================================================
        // 步骤 5【核心】：一条 UPDATE 重算整棵子树
        // =================================================================
        int rebuiltDescendants = nodeMapper.rebuildSubtreeAncestors(oldPrefix, newPrefix, operatorId);
        // 【+1 的来源】步骤 5 命中的是全部后代；被移动节点自身由步骤 4 单独更新。
        // §3.4 响应字段说明：affectedNodeCount「含被移动节点自身」。这【不是】差一错误
        int affectedNodeCount = rebuiltDescendants + 1;

        // =================================================================
        // 步骤 6：维护冗余计数
        // =================================================================
        nodeMapper.addChildCount(oldParentId, -1);
        nodeMapper.addChildCount(toParentId, 1);

        if (movedStudentCount > 0) {
            // 【绝不可用 FIND_IN_SET】理由见 OrgNodeMapper#addStudentCount：
            // 它是列上的函数、必然全表扫，而这条 UPDATE 跑在已持有 FOR UPDATE 锁的事务里，
            // 等于每次分配导师都把整张 org_node 锁一遍，并与步骤 1 的加锁顺序形成死锁。
            // ancestors 本就是逗号串，Java 侧 split 后用 IN 精确锁定几行
            nodeMapper.addStudentCount(ancestorChainOf(moving.getAncestors(), oldParentId), -movedStudentCount);
            nodeMapper.addStudentCount(ancestorChainOf(target.getAncestors(), toParentId), movedStudentCount);

            // 【本项不在 §3.1.3 的 7 步模板里，是有意增补，不是照抄遗漏】
            // 依据两条：① DDL 对 org_teacher.student_count 的列注释「与 org_node.student_count
            // 同源同步；分配/转交/调岗时维护」；② 04-实施计划.md 模块 07「对外产出 · 冗余维护」
            // 那一行：「统一在 06 的移动事务与本模块建删事务内」。
            // 而分配导师/转交/调岗全是本方法的封装，模块 07 没有别的钩子能补这一笔。
            // 父节点不是教师时匹配 0 行、静默无事发生（org_teacher 有 uk_node_id）
            memberMapper.addTeacherStudentCount(oldParentId, -movedStudentCount);
            memberMapper.addTeacherStudentCount(toParentId, movedStudentCount);
        }

        // =================================================================
        // 步骤 7：写异动轨迹（教师调岗只写这一条，随行学员不逐条写）
        // =================================================================
        int changeType = inferChangeType(moving.getNodeType(), target.getNodeType());
        OrgNodeChangeLog changeLog = changeLogWriter.write(
                movingNodeId, changeType, oldParentId, toParentId,
                opts.getReason(), moving.getTenantId());

        // =================================================================
        // 响应：跨管辖授权清单（同事务内算，见下方注释）
        // =================================================================
        List<OutOfScopeGrantVO> outOfScopeGrants = collectOutOfScopeGrants(movingNodeId, newPrefix);
        if (opts.isRevokeOutOfScopeGrants() && !outOfScopeGrants.isEmpty()) {
            // 【本模块不执行回收】04-实施计划.md 模块 06 规则 8 逐字：「本模块先把字段与开关
            // 做出来，级联回收动作在模块 11 接上」，且工单「涉及表」把 org_resource_grant
            // 列在【只读】栏。在这里写一段撤销就是越过工单替模块 11 做设计。
            // 留一条 WARN 而不是静默：调用方传了 true 却什么都没发生，必须有人看得见
            log.warn("revokeOutOfScopeGrants=true 但本模块只做字段与开关，未执行回收"
                            + "（04-实施计划.md 模块 06 规则 8，级联回收在模块 11）："
                            + "nodeId={} 跨管辖授权 {} 条，可经 03-02 接口 39 手动撤销",
                    movingNodeId, outOfScopeGrants.size());
        }

        NodeMovedVO vo = buildVO(moving, target, newSelfAncestors, changeType,
                affectedNodeCount, outOfScopeGrants, changeLog, oldParentId);

        // =================================================================
        // 事务【提交之后】：清缓存 + 埋点
        // =================================================================
        registerAfterCommit(movingNodeId, affectedNodeCount, OrgNode.depthOf(newSelfAncestors) + 1);
        return vo;
    }

    // =====================================================================
    // 校验的三段实现
    // =====================================================================

    /**
     * 校验 4 防成环 —— 用 02-数据库设计 §3.1.4 给出的<b>等价纯 Java 写法</b>，
     * 违反 → {@code 10103}。
     *
     * <p>§3.1.4 原文：「<b>等价的纯 Java 写法</b>（避免多一次查询）：拿到
     * {@code targetParent.ancestors} 后判断
     * {@code targetParentId != movingId && !splitToSet(targetParent.ancestors).contains(movingId)}，
     * <b>语义完全一致</b>」。<b>这是分册给的写法，不是为了绕开检查②</b>
     * （后者会 grep 代码里的 {@code FIND_IN_SET}）。
     *
     * <h2>它在这里不只是「少一次查询」，而是<b>更正确</b></h2>
     * <p>{@code target.getAncestors()} 取自<b>步骤 1 {@code FOR UPDATE} 锁住的那一行</b>，
     * 判定用的就是<b>锁内快照</b>；再发一条 SELECT 反而是在锁外读一次可能已被别人改过的值。
     * §3.1.4 与 00-通用约定 §7.5 都要求「必须在拿到锁之后校验」，本写法把这条要求
     * 从「时机对」加强成「数据同源」。
     *
     * <h2>两个条件缺一不可</h2>
     * <ul>
     *   <li>{@code targetParentId != movingId} 挡住<b>移到自己身上</b>；
     *   <li>{@code !ancestors.contains(movingId)} 挡住<b>移到自己的后代下面</b>。
     * </ul>
     * <b>只写后者会漏掉自环</b> —— 节点自身的 {@code ancestors} 不含自己，
     * 所以「把 A 移到 A 下面」在第二个条件上永远判为通过。
     */
    private void assertNoCycle(OrgNode moving, OrgNode target) {
        if (target.getId().equals(moving.getId())) {
            throw new BizException(ErrorCode.NODE_MOVE_WOULD_CREATE_CYCLE);
        }
        if (NodePath.parseAncestorIds(target.getAncestors()).contains(moving.getId())) {
            throw new BizException(ErrorCode.NODE_MOVE_WOULD_CREATE_CYCLE);
        }
    }

    /**
     * 校验 10：被移动的<b>学生</b>节点必须是在读（{@code org_student.status = 0}），
     * 否则 {@code 10203}。
     *
     * <p><b>查不到档案行时放行，不判 10203</b>：03-01 §2.2 允许超管经
     * {@code /system/users} 建出没有 {@code org_student} 档案的学生节点
     * （F-22 未定案，模块 03 已按「保留现状」落地）。那种节点<b>不是</b>「已退课/已归档」，
     * 用 {@code 10203}（「学生已归档/已退课」）拒绝它会给出一句与事实不符的提示；
     * 而它本来就不占额度、不在 {@code /org/students} 里 —— 是 F-22 要处置的孤儿数据，
     * <b>不是本接口要拦的东西</b>。
     */
    private void assertStudentActive(OrgNode moving) {
        if (moving.getNodeType() == null || moving.getNodeType() != NodePath.NODE_TYPE_STUDENT) {
            return;
        }
        Integer status = memberMapper.selectStudentStatus(moving.getId());
        if (status != null && status != 0) {
            throw new BizException(ErrorCode.STUDENT_ARCHIVED_OR_QUIT);
        }
    }

    /**
     * 深度上限 50 级（契约 §2.3 约束 5），超限 <b>400</b>。
     *
     * <pre>移动后子树最深处的深度 = 目标父深度 + 1 + (子树最深深度 - 被移动节点深度)</pre>
     *
     * <p><b>「子树最深深度」必须在锁内取</b>：锁外取到的是旧值，并发下另一个事务可能刚往
     * 这棵子树里又插/移进了更深的一层，于是这次移动放过了一棵实际会超限的树 ——
     * 第 51 级在写入时 {@code Data too long} 让整个复合事务回滚，而契约 §2.3 约束 5
     * 要求服务层校验的理由正是「那种失败点难以定位」。本方法在步骤 2 内被调用，锁已持有。
     */
    private void assertDepthWithinLimit(OrgNode moving, OrgNode target) {
        Integer deepest = nodeMapper.selectMaxSubtreeDepth(moving.selfPrefix());
        int relativeHeight = deepest == null ? 0 : deepest - moving.depth();
        int newDeepest = target.depth() + 1 + relativeHeight;
        if (newDeepest > OrgNode.MAX_DEPTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "移动后组织树深度将达 " + newDeepest + " 级，超过上限 "
                            + OrgNode.MAX_DEPTH + " 级（契约 §2.3 约束 5）");
        }
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /**
     * 加锁用的 id 列表，<b>升序、去重</b>：<b>本事务全部点写入的行，一个不少</b>。
     *
     * <pre>
     * 被移动节点          （步骤 4 改它的 parent_id / ancestors）
     * 旧父 + 它的祖先链    （步骤 6 减 child_count / student_count）
     * 新父 + 它的祖先链    （步骤 6 加 child_count / student_count）
     * </pre>
     *
     * <p>旧父与它的祖先链<b>就是</b> {@code moving.ancestors} 拆出来的那串（被移动节点的
     * {@code ancestors} 以旧父 id 结尾）；新父那条同理由 {@code target} 拆出。
     * 首位平台根哨兵 {@code 0} 被 {@link NodePath#parseAncestorIds} 跳过 ——
     * 它不属于任何租户，步骤 6 本来也不写它。
     *
     * <h2>为什么不是只锁「被移动节点 + 目标父」</h2>
     * <p>02-数据库设计 §3.1.3 的模板步骤 1 只锁那两行，而步骤 6 要写<b>旧父</b>与
     * <b>两条祖先链</b> —— 那些行<b>不在被排序的集合里</b>。10 个并发事务交叉移动时，
     * 实测 6/10 被 InnoDB 判死锁回滚，{@code SHOW ENGINE INNODB STATUS} 的环里逐字就是
     * {@code UPDATE org_node SET student_count ... WHERE id IN (...)} 与
     * {@code UPDATE org_node SET child_count ... WHERE id = 旧父}。
     *
     * <p>04-实施计划.md <b>§D 前置风险项 → R2 →「撞车后的影响面」那一行</b>把这个现象和它的处置写死了：
     * 「<b>若 10 并发下出现死锁，说明 id 升序加锁没有覆盖全部加锁点</b>——这属于
     * <b>铁律 1 未落地，不可上线</b>」。所以这里做的<b>不是重新设计模板，而是把模板的
     * 硬要求 2（「加锁顺序固定为 id 升序」）落实到它自己后面几步真正写到的每一行</b>。
     * 扩集合后同一并发用例 <b>0 死锁</b>。
     *
     * <p>代价：锁的行数从 2 涨到「2 + 两条祖先链」（各约 5 行，且两条常有公共前缀），
     * 仍是个位数点查；换来的是<b>本事务的全部点写入都在一次有序加锁里完成</b>，
     * 后面几步再写它们时不必新取任何锁。
     *
     * <h2>还有一处加锁点不在这个集合里：步骤 5 的子树范围 UPDATE</h2>
     * <p>它按 {@code idx_ancestors} 范围扫描加锁，<b>顺序由索引决定而不是 id</b>。
     * 要覆盖它就得把整棵子树（§3.1.3 要点 3：单租户上限约 1.1 万行）预先按 id 锁一遍，
     * 那是另一个量级的代价，且模板明确把这一步设计成一条范围 UPDATE。
     * 残留风险：<b>两个移动的子树相互嵌套时仍可能死锁</b>（A 在范围更新中途撞上 B 锁住的行），
     * 而<b>当前的并发用例覆盖不到它</b>（那 10 条移动的子树互不嵌套）。
     *
     * <p><b>已登记在 04-实施计划.md §D 前置风险项 R2 的表格之后</b>（「R2 的一半已在模块 06
     * 落地时提前发生」那一段），并写明 R2 的压测<b>必须把「嵌套子树并发移动」这一形态单独跑一遍</b>。
     * <b>不在本模块自行改设计</b>：要覆盖它就得预锁整棵子树，那是另一个量级的代价，
     * 且模板明确把步骤 5 设计成一条范围 UPDATE。
     */
    private static List<Long> lockIds(OrgNode moving, OrgNode target) {
        Set<Long> ids = new TreeSet<>();                      // TreeSet：天然升序 + 去重
        ids.add(moving.getId());
        ids.addAll(NodePath.parseAncestorIds(moving.getAncestors()));   // 旧父 + 其祖先链
        ids.add(target.getId());
        ids.addAll(NodePath.parseAncestorIds(target.getAncestors()));   // 新父的祖先链
        return new ArrayList<>(ids);
    }

    private static OrgNode pick(List<OrgNode> rows, Long id) {
        for (OrgNode row : rows) {
            if (row.getId().equals(id)) {
                return row;
            }
        }
        return null;
    }

    /**
     * 一条祖先链上要维护 {@code student_count} 的节点 id：<b>父节点自身 + 它的全部祖先</b>。
     *
     * @param childAncestors 子节点的 {@code ancestors}（= 父节点的 {@code ancestors} + ',' + 父 id），
     *                       因此拆出来天然含父节点自身
     *
     * <p>{@link NodePath#parseAncestorIds} 会<b>跳过首位哨兵 {@code 0}</b>，这正是要的：
     * 平台根不属于任何租户，它的 {@code student_count} 没有语义，
     * 而租户插件也会把那一行挡在 UPDATE 之外（{@code tenant_id} 对不上）。
     */
    private static List<Long> ancestorChainOf(String childAncestors, Long parentId) {
        List<Long> ids = new ArrayList<>(NodePath.parseAncestorIds(childAncestors));
        if (parentId != null && parentId != OrgNode.PLATFORM_ROOT_ID && !ids.contains(parentId)) {
            ids.add(parentId);
        }
        return ids;
    }

    /**
     * 异动类型的自动推断（03-02 §3.4 映射表）。
     *
     * <pre>
     * 学生(3) → 教师(2)     = 2 分配导师
     * 学生(3) → 管理员(1)   = 3 转交管理员
     * 教师(2) → 管理员(1)   = 4 教师调岗（其名下学员子树整体跟随，但只写这一条）
     * 管理员(1) → 管理员(1) = 8 节点移动
     * </pre>
     *
     * <p>兜底取 {@code 8 节点移动}：映射表覆盖了承载规则允许的全部组合，
     * 走到兜底说明有人放宽了 {@link NodeTypeRule}，那时这里也该跟着改 ——
     * 与其抛异常让一次合法移动失败，不如落一条语义最泛的轨迹并留在这里等改。
     */
    private static int inferChangeType(Integer movingType, Integer targetType) {
        if (movingType == null || targetType == null) {
            return OrgNodeChangeLog.CHANGE_TYPE_NODE_MOVE;
        }
        if (movingType == NodePath.NODE_TYPE_STUDENT && targetType == NodePath.NODE_TYPE_TEACHER) {
            return OrgNodeChangeLog.CHANGE_TYPE_ASSIGN_TEACHER;
        }
        if (movingType == NodePath.NODE_TYPE_STUDENT && targetType == NodePath.NODE_TYPE_ADMIN) {
            return OrgNodeChangeLog.CHANGE_TYPE_TRANSFER_ADMIN;
        }
        if (movingType == NodePath.NODE_TYPE_TEACHER && targetType == NodePath.NODE_TYPE_ADMIN) {
            return OrgNodeChangeLog.CHANGE_TYPE_TEACHER_REASSIGN;
        }
        return OrgNodeChangeLog.CHANGE_TYPE_NODE_MOVE;
    }

    /**
     * 跨管辖授权清单（契约 §2.5 规则 9）。
     *
     * <p><b>在同一事务内、步骤 5 之后算</b>：判定依据是移动<b>之后</b>的祖先链，
     * 而同事务读得到自己尚未提交的写入。不能推到提交后 —— 它要进本次响应。
     *
     * <p>判定：授权人所在节点在移动后<b>既不是</b>目标节点自身、<b>也不在</b>其祖先链上。
     * 口径的另一半（资源 {@code owner_node_id} 与有效授权链）需要模块 08/09/10 的表，
     * 不在本模块涉及表内，由模块 11 补齐 —— 见 {@code NodeGrantScopeMapper} 类注释。
     */
    private List<OutOfScopeGrantVO> collectOutOfScopeGrants(Long movingNodeId, String newPrefix) {
        List<OutOfScopeGrantVO> result = new ArrayList<>();
        for (NodeGrantScopeMapper.GrantScopeRow row
                : grantScopeMapper.selectSubtreeGrants(movingNodeId, newPrefix)) {
            Long granterNodeId = row.getGranterNodeId();
            if (granterNodeId == null) {
                // 授权人账号已删除：谁授的已无从判定，不当作跨管辖（避免把一条查不清的
                // 记录塞进操作者的待办）。它属于「悬挂授权」，归模块 11 的巡检
                continue;
            }
            if (granterNodeId.equals(row.getTargetNodeId())) {
                continue;
            }
            if (NodePath.parseAncestorIds(row.getTargetAncestors()).contains(granterNodeId)) {
                // 授权人仍在祖先链上 —— 管辖关系没变
                continue;
            }
            OutOfScopeGrantVO vo = new OutOfScopeGrantVO();
            vo.setResourceType(row.getResourceType());
            vo.setResourceId(row.getResourceId());
            // resourceName 在 crs_course / qb_question / vod_video 里，不在本模块涉及表内
            vo.setResourceName(null);
            vo.setTargetNodeId(row.getTargetNodeId());
            vo.setTargetNodeName(row.getTargetNodeName());
            result.add(vo);
        }
        return result;
    }

    private NodeMovedVO buildVO(OrgNode moving, OrgNode target, String newSelfAncestors,
                                int changeType, int affectedNodeCount,
                                List<OutOfScopeGrantVO> grants, OrgNodeChangeLog changeLog,
                                Long oldParentId) {
        NodeMovedVO vo = new NodeMovedVO();
        vo.setNodeId(moving.getId());
        vo.setNodeName(moving.getNodeName());
        vo.setNodeType(moving.getNodeType());
        vo.setFromParentId(oldParentId);
        vo.setFromParentName(nodeNameOf(oldParentId));
        vo.setToParentId(target.getId());
        vo.setToParentName(target.getNodeName());
        vo.setNewAncestors(newSelfAncestors);
        vo.setChangeType(changeType);
        vo.setAffectedNodeCount(affectedNodeCount);
        vo.setOutOfScopeGrants(grants);
        vo.setOutOfScopeGrantCount(grants.size());
        // changeTime 由 NodeChangeLogWriter 从库里读回（只认数据库这一个时钟）
        vo.setChangeTime(changeLog.getChangeTime());
        return vo;
    }

    private String nodeNameOf(Long nodeId) {
        if (nodeId == null || nodeId == OrgNode.PLATFORM_ROOT_ID) {
            return null;
        }
        OrgNode node = nodeMapper.selectById(nodeId);
        return node == null ? null : node.getNodeName();
    }

    /**
     * 注册「事务提交之后」的两件事。
     *
     * <h2>为什么是 {@code afterCommit} 而不是「方法返回之后」</h2>
     * <p>本方法可能被<b>模块 07 的更外层事务</b>包着（分配导师、转交管理员都会在自己的
     * 事务里调它）。那时「本方法返回」离提交还早得很，此刻清缓存等于在提交前清 ——
     * 而那一瞬间别的请求会用<b>旧数据</b>把缓存重新填回来，于是移动做完了、缓存却是错的，
     * 且再也不会自己好。{@code afterCommit} 在<b>最外层</b>提交后才触发，两种调用形态下都对。
     *
     * <h2>{@code evictSubtree} 内部会查库，那时查到的正是我们要清的那批</h2>
     * <p>它用<b>重算之后的新前缀</b>取子树 id 再逐个删键。提交已经完成，
     * 这次查询走的是新连接、看到的是新树 —— <b>正好是被移动的那棵子树</b>。
     * （担心「提交后再查会不会拿错」是多余的：拿错只会发生在<b>提交前</b>查，
     * 那时新前缀在别的连接上还不可见。{@code NodeAncestorCache#evictSubtree}
     * 的类注释把这条写作「这也是必须在事务提交后调用的原因之一」。）
     *
     * <h2>没有事务时直接执行</h2>
     * <p>{@code isSynchronizationActive()} 为假只可能出现在「有人不带事务调本方法」的情形，
     * 而本方法自带 {@code @Transactional}，正常路径下恒为真。留这条分支是为了不让
     * 一次异常的调用方式变成一次<b>静默不清缓存</b>。
     */
    private void registerAfterCommit(Long movingNodeId, int affectedNodeCount, int newDepth) {
        Runnable action = () -> {
            nodeAncestorCache.evictSubtree(movingNodeId);
            // 契约 §7.1 表格第 9 行的两个指标。放在提交后：回滚掉的移动不该被计进去
            meterRegistry.summary(MetricsRegistry.TREE_MOVE_DEPTH).record(newDepth);
            meterRegistry.summary(MetricsRegistry.TREE_MOVE_SUBTREE_SIZE).record(affectedNodeCount);
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
