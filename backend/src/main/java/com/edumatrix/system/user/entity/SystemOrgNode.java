package com.edumatrix.system.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

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
 * <p><b>交接方式（模块 06 请照此执行）</b>：{@code org/node/} 建成
 * {@code NodeCreateService} / {@code NodeDeleteService} 后，<b>删除本类、
 * {@link SystemOrgNodeChangeLog}、{@code SystemOrgNodeMapper}、
 * {@code SystemOrgNodeChangeLogMapper} 与 {@code PlatformNodeWriter}</b>，
 * {@code system/user} 改调对方 Service。届时检查③仍禁止直接 import 实体，
 * 所以对方 Service 要返回自己的 DTO（与 {@code AuthUser} 注释里写的合并条件一致）。
 *
 * <p><b>本类与将来 {@code org/node/entity/OrgNode} 并存期间会有两个 {@code org_node} 实体</b>
 * —— 这是已知代价，不是漏改。代价可控的原因是本类<b>只声明 {@code PlatformNodeWriter}
 * 真正读写的列</b>：{@code student_count} 等不在其中（口径见 {@code PlatformNodeWriter}）。
 */
@TableName("org_node")
public class SystemOrgNode extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 平台根节点（契约 §2.1：全表唯一一行 {@code id = 0}）。超管建平台级账号时挂它下面。 */
    public static final long PLATFORM_ROOT_ID = 0L;

    /**
     * 树深度上限 50 级（契约 §2.3 约束 5）。
     *
     * <p>{@code ancestors} 是 {@code VARCHAR(1000)}，雪花 ID 19 位 + 逗号 = 20 字符/级。
     * <b>必须在服务层校验</b>：不校验的话第 51 级会在写入时 {@code Data too long}
     * 让整个事务回滚，而那种失败点难以定位。
     */
    public static final int MAX_DEPTH = 50;

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
