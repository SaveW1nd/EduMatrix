package com.edumatrix.org.node.dto;

/**
 * 组织树查询参数（03-02 §3.1）。
 *
 * <h2>⚠ {@code parentId} 与 {@code deep} 在分册的参数表里没有，在说明段里有</h2>
 * <p>§3.1 的说明段逐字：「<b>默认懒加载</b> —— 不传 <b>{@code parentId}</b> 时只返回调用者
 * 所在节点的<b>直接子节点</b>；前端展开某节点时再带 {@code parentId} 拉下一层」、
 * 「传 <b>{@code deep=true}</b> 可一次性取整棵子树，此时<b>必须同时传 {@code maxDepth}
 * 或 {@code nodeTypes}</b>，且服务端硬上限 2000 个节点，超出返回 {@code 400}」。
 * 而请求参数表里<b>这两个字段都不存在</b>，响应示例给的又是三层嵌套。
 *
 * <p>与 <b>F-20</b>（§9.6 说明段有 {@code crossScope}、参数表没有）<b>同一形状</b>：
 * 分册自身不一致，说明段有完整推导（机构根管理员的子树 = 全机构约 1.1 万节点，
 * 一次性返回 5~8 MB，而这是管理端登录后的第一个请求），参数表是没跟上的那一半。
 * <b>已登记为 04-实施计划.md §E 的 F-26，实现按说明段，分册待订正。</b>
 * 接口总数不变（仍 160）—— 改的是两个查询参数，不新增接口。
 */
public class NodeTreeQuery {

    /** 树根节点 ID，默认当前用户所在节点。不在子树内 → {@code 10107}。 */
    private Long rootId;

    /**
     * 懒加载的展开点：只返回它的<b>直接子节点</b>。不传时展开点即树根。
     *
     * <p>命中 {@code idx_tenant_parent_sort}，单层毫秒级。
     */
    private Long parentId;

    /**
     * 一次性取整棵子树。<b>为 true 时必须同时传 {@code maxDepth} 或 {@code nodeTypes}</b>，
     * 否则 {@code 400}。
     */
    private Boolean deep;

    /**
     * 节点类型过滤，逗号分隔，取值 {@code 1} 管理员 {@code 2} 教师 {@code 3} 学生。
     * 不传返回全部类型。
     *
     * <p>筛选时若某节点被排除，<b>其子节点一并不返回</b>（保持树的连通性）。
     */
    private String nodeTypes;

    /** 是否包含 {@code status=1} 已停用节点，默认 {@code false}。 */
    private Boolean includeDisabled;

    /** 相对树根的最大返回深度，{@code 0} 或不传表示不限。 */
    private Integer maxDepth;

    /** 节点名称模糊匹配；命中节点及其<b>全部祖先链</b>一并返回，未命中分支不返回。 */
    private String keyword;

    public Long getRootId() {
        return rootId;
    }

    public void setRootId(Long rootId) {
        this.rootId = rootId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Boolean getDeep() {
        return deep;
    }

    public void setDeep(Boolean deep) {
        this.deep = deep;
    }

    public String getNodeTypes() {
        return nodeTypes;
    }

    public void setNodeTypes(String nodeTypes) {
        this.nodeTypes = nodeTypes;
    }

    public Boolean getIncludeDisabled() {
        return includeDisabled;
    }

    public void setIncludeDisabled(Boolean includeDisabled) {
        this.includeDisabled = includeDisabled;
    }

    public Integer getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(Integer maxDepth) {
        this.maxDepth = maxDepth;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    /** 是否要求整棵子树（{@code deep=true}）。 */
    public boolean isDeep() {
        return Boolean.TRUE.equals(deep);
    }

    public boolean isIncludeDisabled() {
        return Boolean.TRUE.equals(includeDisabled);
    }
}
