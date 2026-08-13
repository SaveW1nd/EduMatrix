package com.edumatrix.common.subtree.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.edumatrix.common.subtree.NodePath;

/**
 * 子树查询专用 Mapper —— <b>公共层自用，业务模块不要注入它</b>。
 *
 * <p>业务侧一切「我能看到哪些数据」的判定都走
 * {@code SubtreeScopeHelper}；组织树的读写接口是模块 06 的 {@code org/node/}。
 * 这里只有数据权限判定必需的三条查询，故意不做成完整的 {@code OrgNodeMapper}。
 *
 * <p><b>三条查询对应契约 §2.4 选路表的三条执行路径</b>，各自命中一个索引。
 * 全表没有一处 {@code FIND_IN_SET} —— 契约 §7.1 写死了：<b>它出现在慢查询日志中即视为缺陷</b>。
 *
 * <p><b>租户条件由插件自动注入</b>，这里一个字都不写。手写 {@code tenant_id = ?}
 * 会与插件注入的条件重复，更糟的是会让人以为「这里写了所以别处不用写」。
 *
 * <p>用注解而不是 XML：这三条 SQL 是数据权限的判定依据，
 * 放在与它们唯一使用者相邻的位置比放进 {@code resources/mapper/} 更难被改错。
 */
@Mapper
public interface OrgNodeSubtreeMapper {

    /**
     * 取单个节点的路径信息。子树前缀与「目标是否在我子树内」的判定都以它为起点。
     *
     * <p>不加 {@code deleted_at = 0} 之外的任何过滤：停用（{@code status = 1}）的节点
     * <b>仍然在树上</b>，停用管的是能不能登录（契约 §2.3），不是能不能被看到。
     */
    @Select("SELECT id, parent_id AS parentId, ancestors, node_type AS nodeType, tenant_id AS tenantId "
            + "FROM org_node WHERE id = #{nodeId} AND deleted_at = 0")
    NodePath selectPath(@Param("nodeId") Long nodeId);

    /**
     * 路径②：前缀 LIKE 一次性取整棵子树（管理员 / 超管）。命中 {@code idx_ancestors(ancestors(255))}。
     *
     * <p><b>两个条件缺一不可</b>：直接子节点的 {@code ancestors} 恰好等于 {@code P}
     * （后面没有逗号），只写 LIKE 会<b>漏掉整层直接子节点</b>。
     *
     * <p><b>LIKE 必须以逗号收边</b>：写成 {@code LIKE CONCAT(P, '%')} 时，
     * {@code P} 结尾若是 {@code ...,100}，会误命中 {@code ...,1001}。
     * 雪花 ID 虽然等长，但平台根的 id 是 {@code 0}，长度并不齐。
     */
    @Select("SELECT id FROM org_node "
            + "WHERE deleted_at = 0 AND (ancestors = #{prefix} OR ancestors LIKE CONCAT(#{prefix}, ',%'))")
    List<Long> selectSubtreeIdsByPrefix(@Param("prefix") String prefix);

    /**
     * 路径①：按父节点直查（教师取名下学员）。命中 {@code idx_parent_type(parent_id, node_type)}。
     *
     * <p>教师节点下只能挂学生（契约 §2.3 结构约束 1），因此教师的<b>子树恰好等于直接子节点</b>，
     * 无需走前缀 LIKE。这是全系统执行频率最高的一条数据权限查询，所以它单独占一条路径。
     */
    @Select("SELECT id FROM org_node "
            + "WHERE parent_id = #{parentId} AND node_type = #{nodeType} AND deleted_at = 0")
    List<Long> selectChildIdsByType(@Param("parentId") Long parentId, @Param("nodeType") Integer nodeType);

    /**
     * 路径③：逐层展开（树懒加载、面包屑）。命中 {@code idx_tenant_parent_sort(tenant_id, parent_id, sort)}。
     */
    @Select("SELECT id FROM org_node WHERE parent_id = #{parentId} AND deleted_at = 0 ORDER BY sort ASC, id ASC")
    List<Long> selectChildIds(@Param("parentId") Long parentId);
}
