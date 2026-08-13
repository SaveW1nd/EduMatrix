package com.edumatrix.common.subtree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 节点在树上的位置：{@code id / parentId / ancestors / nodeType / tenantId}。
 *
 * <p>只承载数据权限判定需要的几列，不是 {@code org_node} 的完整实体 ——
 * 那个归模块 06（{@code org/node/entity/OrgNode}）。
 *
 * <h2>ancestors 的首位是哨兵，不是可读节点</h2>
 * <p>{@code ancestors} 形如 {@code 0,机构id,校区id,...}，首位的 {@code 0} 是<b>平台根哨兵</b>
 * （{@code node_type = 0}、不属于任何租户），不是一个可以展示给租户的节点。
 * 契约 §2.9 定案：<b>不放行它，且不需要放行</b>，因为
 * ① 登录停用校验的条件是 {@code node_type = 1 AND status = 1}，哨兵是 0，永远不命中；
 * ② 面包屑的口径是「自<b>租户根</b>到自身」，平台根出现在租户面包屑里反而是越界。
 *
 * <p>因此 {@link #ancestorIds()} <b>跳过首位哨兵</b>。由此带来的现象是：
 * 按 {@code IN (拆出的全部 id)} 查节点名称时，<b>返回行数会比 id 数少 1</b>（哨兵行被租户
 * 插件过滤掉）。<b>这是正确行为，不是 bug —— 不要"修"成放行哨兵。</b>
 * {@link #ancestorIds()} 已经把它去掉了，所以正常使用本类不会踩到这个坑。
 */
public class NodePath {

    private Long id;
    private Long parentId;
    private String ancestors;
    private Integer nodeType;
    private Long tenantId;

    /** 平台根哨兵的节点 ID（契约 §2.1：全表唯一一行 {@code id = 0}）。 */
    public static final long ROOT_SENTINEL_ID = 0L;

    /** {@code node_type}：0 平台超管 / 1 管理员 / 2 教师 / 3 学生（契约 §5，与 {@code user_type} 一致）。 */
    public static final int NODE_TYPE_PLATFORM = 0;
    public static final int NODE_TYPE_ADMIN = 1;
    public static final int NODE_TYPE_TEACHER = 2;
    public static final int NODE_TYPE_STUDENT = 3;

    public NodePath() {
    }

    public NodePath(Long id, Long parentId, String ancestors, Integer nodeType, Long tenantId) {
        this.id = id;
        this.parentId = parentId;
        this.ancestors = ancestors;
        this.nodeType = nodeType;
        this.tenantId = tenantId;
    }

    /**
     * 本节点的<b>自身路径前缀</b> {@code P}，用于前缀 LIKE 取子树。
     *
     * <pre>
     * P = (ancestors = '' ? CAST(id AS CHAR) : CONCAT(ancestors, ',', id))
     * </pre>
     *
     * <p><b>空串分支不可省</b>（契约 §2.4 选路表原文）：平台根 {@code ancestors = ''}、
     * {@code id = 0}，若不分支直接 CONCAT 会得到 {@code ',0'}，
     * 而机构根节点的 {@code ancestors = '0'} 既不等于 {@code ',0'}、也不 LIKE {@code ',0,%'} ——
     * <b>超管取全平台会静默返回空集</b>。空集不报错，只是页面上什么都没有。
     */
    public String selfPrefix() {
        if (ancestors == null || ancestors.isEmpty()) {
            return String.valueOf(id);
        }
        return ancestors + "," + id;
    }

    /**
     * 祖先节点 ID 列表，<b>已跳过首位哨兵 {@code 0}</b>，根在前。
     *
     * <p>登录时的祖先链校验、面包屑、冻结集求交都用它。
     */
    public List<Long> ancestorIds() {
        return parseAncestorIds(ancestors);
    }

    /**
     * 解析 {@code ancestors} 逗号串为 ID 列表，<b>跳过首位平台根哨兵</b>。
     *
     * @param ancestors 形如 {@code 0,100,101}；平台根自身为空串
     */
    public static List<Long> parseAncestorIds(String ancestors) {
        if (ancestors == null || ancestors.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = ancestors.split(",");
        List<Long> ids = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            long value = Long.parseLong(trimmed);
            if (value == ROOT_SENTINEL_ID) {
                // 首位哨兵：路径标记，不是可读节点
                continue;
            }
            ids.add(value);
        }
        return ids;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Integer getNodeType() {
        return nodeType;
    }

    public void setNodeType(Integer nodeType) {
        this.nodeType = nodeType;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}
