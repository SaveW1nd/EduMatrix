package com.edumatrix.system.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;
import com.edumatrix.common.subtree.OrgTreeShape;

/**
 * {@code org_node} 的<b>窄写模型</b>，只服务 03-01 §2.2 建号与 §2.4 删号两条路径。
 *
 * <h2>⚠ 临时构件：{@code org} 领域建成后本类应删除</h2>
 * <p>03-01 §2.2 的副作用要求「同一事务内创建 {@code org_node} 节点并回写
 * {@code sys_user.node_id}」，§2.4 要求「同时逻辑删除其 {@code org_node} 节点」——
 * 而 {@code org} 领域要到<b>模块 06/07</b> 才存在，此刻没有对方的 Service 可调，
 * 05-工程结构.md §A1 的第三条硬约束又禁止领域包互相 import
 * （{@code scripts/check_backend_conventions.sh} 检查③）。
 *
 * <p><b>处境与模块 02 把 {@code AuthUser} 放在 {@code auth/} 完全同构</b>，处置也照那个先例：
 * 落在本领域、写窄实体、留标记。三条路的取舍：
 * <ul>
 *   <li><b>放 {@code common/}</b> —— 否决。{@code common} 是模块 01 的唯一产出地，§E 的清单
 *       是<b>基础设施</b>（租户插件、子树、ID、trace）。「按契约 §2.3 校验父子类型、
 *       算 {@code ancestors}、写建档轨迹」是<b>业务逻辑</b>。{@code FrozenNodeCache}
 *       能进 {@code common} 是因为它是纯 Redis 结构 + 一条判定规则，建节点不是。
 *   <li><b>现在就建 {@code org/node/}</b> —— 否决。那是模块 06 的主要落点，工单写着
 *       {@code NodeMoveService} / {@code NodeTypeRule} / {@code ancestors} 重算 / 行锁 /
 *       {@code child_count} 维护。现在写一个半成品占位，等于<b>在没有工单的情况下
 *       替模块 06 做设计决策</b>。
 *   <li><b>窄写入器落 {@code system/user/}（本方案）</b>。
 * </ul>
 *
 * <p><b>交接方式（模块 <s>06</s> 07 请照此执行）</b>：{@code org/node/} 建成
 * {@code NodeCreateService} / {@code NodeDeleteService} 后，<b>删除本类、
 * {@link SystemOrgNodeChangeLog}、{@code SystemOrgNodeMapper}、
 * {@code SystemOrgNodeChangeLogMapper} 与 {@code PlatformNodeWriter}</b>，
 * {@code system/user} 改调对方 Service。届时检查③仍禁止直接 import 实体，
 * 所以对方 Service 要返回自己的 DTO（与 {@code AuthUser} 注释里写的合并条件一致）。
 *
 * <p><b>交接清单在模块 04 之后扩到七个构件</b>（清单必须完整，否则删完前五个会误以为交接完毕）：
 * <ol>
 *   <li>本类、{@link SystemOrgNodeChangeLog}、{@code SystemOrgNodeMapper}、
 *       {@code SystemOrgNodeChangeLogMapper}、{@code PlatformNodeWriter}（模块 03）；
 *   <li>{@code system/user/mapper/StudentQuotaMapper}（模块 03，两条 {@code org_student} /
 *       {@code sys_tenant} 窄只读，见其类注释）；
 *   <li><b>{@code system/tenant/mapper/TenantOrgMapper}（模块 04）</b>——对 {@code org_node} /
 *       {@code org_student} 的四条窄读写（子树节点数、在读学生数、根节点改名、租户删除时
 *       整棵子树级联软删）。模块 06/07 建成后，它们分别对应 {@code org/node} 的节点查询与
 *       改名、{@code org/member} 的学籍统计，届时删除本类并改调对方 Service。
 * </ol>
 * <p><b>{@code PlatformNodeWriter#createTenantRootNode}（模块 04 用）也在交接范围内</b>：
 * 它对应模块 06 的"建节点"能力，只是多带两个写死的例外（节点 id = 租户 id、
 * {@code tenant_id} 取新租户而非父节点）——契约 §2.1 的循环依赖决定的，
 * 移交时这两条例外必须原样保留，否则整条 {@code tenant_id = 根节点 id} 的约定当场破掉且不报错。
 *
 * <p><b>本类与将来 {@code org/node/entity/OrgNode} 并存期间会有两个 {@code org_node} 实体</b>
 * —— 这是已知代价，不是漏改。代价可控的原因是本类<b>只声明 {@code PlatformNodeWriter}
 * 真正读写的列</b>：{@code student_count} 等不在其中（口径见 {@code PlatformNodeWriter}）。
 * <b>模块 06 已建成 {@code org/node/entity/OrgNode}，并存期从那时开始。</b>
 *
 * <h2>⚠ 交接时机订正（模块 06 落地时核实）：是模块 <b>07</b>，不是 06</h2>
 * <p>上面写的「模块 06 建成 {@code NodeCreateService} / {@code NodeDeleteService} 后」
 * <b>找错了模块</b>：模块 06 的六个接口是查询 / 详情 / 修改 / 移动 / 停用 / 重置密码，
 * <b>没有建节点与删节点</b>。03-02 §2.2 原文：「本模块<b>没有独立的『新建节点』接口</b>，
 * 建节点 = 建人，一律走接口 8（新建下级管理员）/ 12（新建教师）/ 17（创建学生）」——
 * 那三个接口和三个删除接口都在<b>模块 07</b> 的工单里
 * （04-实施计划.md 模块 07「涉及表」：写 {@code org_node}、{@code sys_user}、
 * {@code org_teacher}、{@code org_student}…）。
 * <p><b>本清单上的七个构件因此原样留到模块 07</b>，模块 06 一个都没删。
 *
 * <h2>另有一张清单：模块 06 自己的临时构件</h2>
 * <p>见 {@code com.edumatrix.org.node} 的 {@code package-info}（四个：
 * {@code NodeMemberMapper} / {@code NodeGrantScopeMapper} / {@code NodeAccountMapper} /
 * {@code NodeTypeRule} 的第二份实现）。
 * <b>两张清单不合并</b>：本清单是「{@code system} 领域为了碰 {@code org} 的表而开的窄构件」
 * （成因是检查③禁止 {@code system} import {@code org}），
 * 那张是「{@code org/node} 为了碰 {@code org} 其它<b>子域</b>的表而开的」
 * （检查③的 {@code DOMAINS} 是<b>顶层</b>领域包，压根管不到子域）。
 * 成因不同、交接对象不同（本清单 → 模块 07；那张 → 模块 07 与 <b>11</b>）。
 * 混成一张的后果是：删完这七个就以为交接完毕。
 */
@TableName("org_node")
public class SystemOrgNode extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 平台根节点（契约 §2.1：全表唯一一行 {@code id = 0}）。超管建平台级账号时挂它下面。 */
    public static final long PLATFORM_ROOT_ID = 0L;

    /**
     * 树深度上限 —— <b>F-114 由 50 收到 5</b>，与 {@code org/node/entity/OrgNode.MAX_DEPTH}
     * <b>取自同一个常量</b>，不再是两个各写各的数字（理由见 {@link OrgTreeShape}）。
     */
    public static final int MAX_DEPTH = OrgTreeShape.MAX_DEPTH;

    private Long parentId;

    /**
     * 祖级路径逗号串，根在前、<b>不含本节点</b>，如 {@code 0,100,101,205}；
     * 平台根节点自身为空串。
     */
    private String ancestors;

    /** 节点名称。管理员/教师/学生节点填其真实姓名，与 {@code sys_user.real_name} 同步。 */
    private String nodeName;

    /**
     * 0 平台超管 1 管理员 2 教师 3 学生。
     *
     * <p><b>与 {@code sys_user.user_type} 取值完全一致，不做任何映射</b>（契约 §5 / §2.2 参数表）。
     * 承载规则：0 只挂 1；1 可挂 1/2/3；2 只挂 3；3 为叶子。
     */
    private Integer nodeType;

    /** 关联账号 {@code user_id}，与 {@code sys_user.node_id} 互为反向引用，全部非空。 */
    private Long refUserId;

    private Integer sort;

    /**
     * 0 正常 1 停用。
     *
     * <p><b>本模块只写默认值 0，此后任何路径都不改它。</b>契约 §2.3：
     * {@code org_node.status} 是停用的<b>唯一权威</b>，停用/启用一律走 02-组织机构分册
     * 接口 5（节点停用/启用），且要与冻结集的两条顺序约束配合。
     * 本模块的 §2.6 改的是 {@code sys_user.status}（账号级封禁），是另一件事。
     */
    private Integer status;

    /**
     * 直接子节点数（冗余计数）。
     *
     * <p>DDL 注释：「增删/移动子节点时同步维护；{@code > 0} 时禁止删除本节点」。
     * {@code PlatformNodeWriter} 建号时对父节点 +1、删号时对父节点 -1。
     */
    private Integer childCount;

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getAncestors() {
        return ancestors;
    }

    public void setAncestors(String ancestors) {
        this.ancestors = ancestors;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Integer getNodeType() {
        return nodeType;
    }

    public void setNodeType(Integer nodeType) {
        this.nodeType = nodeType;
    }

    public Long getRefUserId() {
        return refUserId;
    }

    public void setRefUserId(Long refUserId) {
        this.refUserId = refUserId;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getChildCount() {
        return childCount;
    }

    public void setChildCount(Integer childCount) {
        this.childCount = childCount;
    }

    /**
     * 本节点的自身路径前缀 {@code P}，即其子节点的 {@code ancestors}。
     *
     * <p><b>空串分支不可省</b>（契约 §2.4 选路表原文，{@code common/subtree/NodePath}
     * 里逐字记过）：平台根 {@code ancestors = ''}、{@code id = 0}，
     * 不分支直接 CONCAT 会得到 {@code ',0'}，而机构根节点的 {@code ancestors = '0'}
     * 既不等于 {@code ',0'} 也不 LIKE {@code ',0,%'} —— 超管取全平台会<b>静默返回空集</b>。
     */
    public String selfPrefix() {
        if (ancestors == null || ancestors.isEmpty()) {
            return String.valueOf(getId());
        }
        return ancestors + "," + getId();
    }

    /** 层级深度 = {@code ancestors} 的段数（平台根为 0）。用于 {@link #MAX_DEPTH} 校验。 */
    public int depth() {
        if (ancestors == null || ancestors.isEmpty()) {
            return 0;
        }
        int depth = 1;
        for (int i = 0; i < ancestors.length(); i++) {
            if (ancestors.charAt(i) == ',') {
                depth++;
            }
        }
        return depth;
    }
}
