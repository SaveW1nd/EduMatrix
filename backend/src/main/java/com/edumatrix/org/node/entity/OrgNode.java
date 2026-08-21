package com.edumatrix.org.node.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.OrgTreeShape;

/**
 * {@code org_node} 统一组织树（契约 §2.3）。<b>每个节点都是一个人</b>，
 * 从平台超管一路到学生，一棵树到底。
 *
 * <h2>与 {@code system/user/entity/SystemOrgNode} 并存是已知代价，不是漏改</h2>
 * <p>那个是模块 03 的<b>窄写模型</b>，只声明 {@code PlatformNodeWriter} 真正读写的列
 * （没有 {@code student_count}）；本类是 {@code org} 领域的完整实体。
 * 检查③禁止 {@code system} import {@code org}，所以在模块 07 把 {@code PlatformNodeWriter}
 * 退休之前两者必须并存 —— 这一条在 {@code SystemOrgNode} 的类注释里已登记过。
 *
 * <h2>{@code node_type} 一经创建不可变更</h2>
 * <p>02-数据库设计 §3.1.5：「<b>直接禁止</b>。人员类型变更（教师转管理员）应走
 * 『改角色 + 移动节点』，不得原地改 {@code node_type}，否则其既有子树可能瞬间违规」。
 * 本模块的 §3.3 修改节点因此只改 {@code node_name} / {@code sort} / {@code remark}。
 */
@TableName("org_node")
public class OrgNode extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 平台根节点（契约 §2.1：全表唯一一行 {@code id = 0}）。 */
    public static final long PLATFORM_ROOT_ID = NodePath.ROOT_SENTINEL_ID;

    /**
     * 租户根节点（= 机构最高管理员本人）的 {@code parent_id}。
     *
     * <p>契约 §2.1：机构根节点 {@code parent_id = 0}、{@code ancestors = "0"}，
     * 且其 {@code id} 恒等于 {@code tenant_id}。§3.4 校验 2 用它判「不得移动租户根节点」。
     */
    public static final long TENANT_ROOT_PARENT_ID = PLATFORM_ROOT_ID;

    /**
     * 树深度上限 50 级（契约 §2.3 约束 5）。
     *
     * <p>{@code ancestors} 是 {@code VARCHAR(1000)}，雪花 ID 19 位 + 逗号 = 20 字符/级。
     * <b>必须在服务层校验</b>：不校验的话第 51 级会在写入时 {@code Data too long}
     * 让整个<b>移动复合事务</b>回滚，而那种失败点难以定位。
     */
    /**
     * 组织树最大层数 —— <b>F-114（2026-08-22 需方定案）由 50 收到 5</b>：
     * <pre>L0 平台根 → L1 机构根 → L2 普通管理员 → L3 教师 → L4 学生</pre>
     * 配合 {@code NodeTypeRule}「机构下只允许一层管理员」，两者合起来才封死深度：
     * 光有本常量的话，管理员仍可嵌套到第 5 层。
     *
     * <p>它的价值<b>不是让断链不发生</b>，而是让每次修复的成本有硬上界 ——
     * 理由见 {@code NodeTypeRule.assertCanBeChildOf} 的类注释。
     */
    public static final int MAX_DEPTH = OrgTreeShape.MAX_DEPTH;

    /** {@code status}：0 正常。 */
    public static final int STATUS_NORMAL = 0;
    /** {@code status}：1 停用（可逆软冻结，配合冻结集，契约 §2.3）。 */
    public static final int STATUS_DISABLED = 1;

    private Long parentId;

    /**
     * 祖级路径逗号串，根在前、<b>不含本节点</b>，如 {@code 0,100,101,205}；
     * 平台根节点自身为空串。
     */
    private String ancestors;

    private String nodeName;

    /** 0 平台超管 1 管理员 2 教师 3 学生（契约 §5，与 {@code sys_user.user_type} 完全一致）。 */
    private Integer nodeType;

    /** 关联账号 {@code user_id}，与 {@code sys_user.node_id} 互为反向引用，<b>恒非空</b>。 */
    private Long refUserId;

    private Integer sort;

    private Integer status;

    /** 直接子节点数（冗余计数；移动事务里旧父 -1、新父 +1）。 */
    private Integer childCount;

    /**
     * 子树内<b>在读</b>学生数（冗余计数，{@code org_student.status = 0} 口径）。
     *
     * <p>口径与 {@code StudentQuotaMapper#countActiveStudents} /
     * {@code TenantOrgMapper#countActiveStudents} <b>必须一致</b>（F-22 已定案）——
     * 同一个数两套算法，查起来极难。
     */
    private Integer studentCount;

    /**
     * 本节点的<b>自身路径前缀</b> {@code P}，即其子节点的 {@code ancestors}。
     *
     * <pre>P = (ancestors = '' ? CAST(id AS CHAR) : CONCAT(ancestors, ',', id))</pre>
     *
     * <p><b>空串分支不可省</b>（契约 §2.4 选路表原文）：平台根 {@code ancestors = ''}、
     * {@code id = 0}，不分支直接 CONCAT 会得到 {@code ',0'}，
     * 而机构根节点的 {@code ancestors = '0'} 既不等于 {@code ',0'} 也不 LIKE {@code ',0,%'}
     * —— 超管取全平台会<b>静默返回空集</b>。
     */
    public String selfPrefix() {
        if (ancestors == null || ancestors.isEmpty()) {
            return String.valueOf(getId());
        }
        return ancestors + "," + getId();
    }

    /** 层级深度 = {@code ancestors} 的段数（平台根为 0，租户根为 1）。 */
    public int depth() {
        return depthOf(ancestors);
    }

    /** 由 {@code ancestors} 算层级深度；空串（平台根）为 0。 */
    public static int depthOf(String ancestors) {
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

    /** 是否为租户根节点（机构最高管理员本人）。§3.4 校验 2：<b>不得被移动</b>。 */
    public boolean isTenantRoot() {
        return parentId != null && parentId == TENANT_ROOT_PARENT_ID;
    }

    public boolean isDisabled() {
        return status != null && status == STATUS_DISABLED;
    }

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

    public Integer getStudentCount() {
        return studentCount;
    }

    public void setStudentCount(Integer studentCount) {
        this.studentCount = studentCount;
    }
}
