package com.edumatrix.common.subtree;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;

/**
 * 组织树<b>形状</b>的唯一事实：树能有多高、机构根怎么认、管理员能不能套管理员。
 *
 * <h2>为什么这三样必须在同一个地方、而且在 {@code common/}</h2>
 * <p>它们各自都<b>已经有两份实现</b>，且两份都不在同一个域里：
 * <ul>
 *   <li>树高：{@code org/node/entity/OrgNode.MAX_DEPTH} 与
 *       {@code system/user/entity/SystemOrgNode.MAX_DEPTH}；</li>
 *   <li>承载规则：{@code org/node/service/NodeTypeRule} 与
 *       {@code system/user/service/PlatformNodeWriter#assertParentAcceptsChild}；</li>
 *   <li>机构根判据：{@link OrgRootGuard}（会话侧）与建/移两条路径（实体侧）。</li>
 * </ul>
 * <p>两份并存<b>不是疏忽</b>：{@code check_backend_conventions.sh} 的检查③ 禁止
 * {@code system} 域 import {@code org} 域。承载规则整体合并是<b>另一轮</b>的事
 * （理由与时机见 {@code NodeTypeRule} 类注释，此处不动它）。
 * 但 F-114 这一轮<b>新加</b>的判定不能再分成两份 —— 分了就会重演那句已经写在
 * {@code NodeTypeRule} 里的警告：「改任一份都要同时改另一份，<b>且不会有任何东西报错</b>」。
 * {@code common/} 不进检查③ 的扫描范围，正是做交汇点用的。
 *
 * <h2>F-114（需方 2026-08-22 定案）：机构下只允许一层管理员，树高最多 5 层</h2>
 * <pre>L0 平台根 → L1 机构根 → L2 普通管理员 → L3 教师 → L4 学生</pre>
 * <p>改之前，树里<b>唯一能无限长的就是「管理员挂管理员」</b>。
 * <b>两条改动缺一不可</b>：光有 {@link #MAX_DEPTH} 的话管理员仍可嵌套到第 5 层，
 * 光有 {@link #assertOnlyOneAdminLayer} 的话教师/学生仍可被挪到更深处。
 *
 * <p><b>需方给的理由，原样登记</b>：授权的规矩是「不能授你自己没有的东西」，
 * 所以要让某节点不悬挂，<b>上级必须先有</b>。任意深度下，补授会一路往上串，
 * <b>补几层没有上界</b>；限死 5 层之后 ——
 * 挪教师 → 断在 L2→L3，补 L2 一个节点（L2 的上级是机构根，永远有）；
 * 挪学生 → 断在 L3→L4，最多补 L3 与 L2 两个。
 * <b>加：最多两个节点，上界固定；去：永远一个动作（级联撤销）。</b>
 *
 * <p><b>深度限制不能让断链不发生</b>，但让每次修复的成本有了硬上界 —— 这才是它的价值。
 */
public final class OrgTreeShape {

    private OrgTreeShape() {
    }

    /**
     * 组织树深度上限 —— <b>F-114 由 50 收到 4</b>。
     *
     * <h2>为什么定案说「5 层」而这里写 4</h2>
     * <p>它拿去和 {@code depth()} 比，而 {@code depth()} 数的是 {@code ancestors} 的段数、
     * <b>平台根记 0</b>：
     * <pre>
     *   L0 平台根   depth 0
     *   L1 机构根   depth 1
     *   L2 管理员   depth 2
     *   L3 教师     depth 3
     *   L4 学生     depth 4   ← 最深的合法节点
     * </pre>
     * 「5 层」数的是<b>路径上的节点个数</b>（含平台根），「4」是同一件事的另一种数法。
     * <b>写 5 会多放行一层</b>，那一层正好是定案要封掉的。
     *
     * <p>原来的 50 来自契约 §2.3 约束 5，是 {@code ancestors VARCHAR(1000)} 的<b>物理</b>上界
     * （雪花 ID 19 位 + 逗号 = 20 字符/级）。物理上界没变，4 是业务上界。
     *
     * <p><b>存量影响</b>：比 4 更深的树会变得既不能再往下建、也不能整棵搬走。
     * 上线前已查过生产：嵌套管理员 0 个，最深就是 L1 机构根，不受影响。
     * 模块 06 的验收夹具原先是 6 层（ROOT→A1→P→A3→T1→S1），已随本轮改形。
     *
     * <h2>⚠ 它在<b>合法树</b>里够不着，别据此以为它没用</h2>
     * <p>{@link #assertOnlyOneAdminLayer} 一旦生效，承载规则自己就把深度封在 4 了：
     * 管理员的父只能是机构根（永远 depth 2）→ 教师的父只能是管理员（永远 3）→
     * 学生是叶子（永远 4）。<b>合法树里根本造不出第 5 层</b>，所以「建人超深」这条路
     * 走不到本常量，会先被 {@code 10105}/{@code 10106}/{@code 10104} 拦下。
     *
     * <p><b>它唯一真正生效的场合是：把一棵【存量的、改形前留下的】深子树整个搬走。</b>
     * 那时 {@code NodeMoveService#assertDepthWithinLimit} 会算出新深度并拒。
     * 用例 {@code NodeMoveValidationIT#movingLegacyDeepSubtreeExceedsDepthLimit} 就守着这一条 ——
     * <b>在它之前，把本常量从 4 改回 50，整套测试是全绿的</b>（M59 实测），
     * 也就是说定案二的「树高」那一半当时没有任何东西守着。
     */
    public static final int MAX_DEPTH = 4;

    /**
     * 「这个节点是不是<b>机构根</b>」的<b>唯一判据</b>。
     *
     * <p><b>机构根节点的 {@code id} 等于它的 {@code tenant_id}</b> —— 契约 §2.1 与
     * 02-数据库设计 §28 逐字写死的两个 ID 例外之一（另一个是平台根固定 {@code id = 0}）。
     * 所以这是<b>一次比较，不查库、不遍历树、不看 {@code ancestors} 前缀</b>。
     *
     * <p><b>⚠ 不要改成按 {@code parent_id == 0} 判</b>：那依赖树的形状
     * （「机构根挂在平台根下」今天成立，但它是建树规则的<b>推论</b>，不是契约事实），
     * 而 {@code id == tenant_id} 是契约<b>直接写死</b>的。两者今天等价、来源不同。
     *
     * <p><b>已知且有意的重合</b>：平台根 {@code id = 0}、{@code tenant_id = 0}，
     * 因此它也满足本判据。承载规则里平台根走的是 {@code NODE_TYPE_PLATFORM} 分支、
     * 到不了 {@link #assertOnlyOneAdminLayer}，所以这里不为它加特例 ——
     * 加了反而要解释「平台根为什么不是机构根」，而那句话在别处没有用处。
     */
    public static boolean isOrgRoot(Long nodeId, Long tenantId) {
        return nodeId != null && tenantId != null && tenantId.equals(nodeId);
    }

    /**
     * F-114：<b>非机构根的管理员，其下不得再挂管理员</b>。
     *
     * <p>只在父子都是管理员（{@code node_type = 1}）时才可能拒绝，其余组合原样放行 ——
     * 「教师下只能挂学生」「学生是叶子」那几条仍归各自那份承载规则判。
     *
     * <p><b>复用现有的「父子类型非法」码 {@code 10104}，不新开一个</b>：它与
     * 「教师名下只能加学员」是同一类判定（<b>父节点决定子节点能是什么</b>），
     * 同一段语义该用同一个码，前端也不必为它加一条新分支。
     *
     * @param parentIsOrgRoot 父节点是否机构根，取自 {@link #isOrgRoot}
     */
    public static void assertOnlyOneAdminLayer(Integer parentType, Integer childType,
                                               boolean parentIsOrgRoot) {
        if (parentType != null && childType != null
                && parentType == NodePath.NODE_TYPE_ADMIN
                && childType == NodePath.NODE_TYPE_ADMIN
                && !parentIsOrgRoot) {
            throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
        }
    }
}
