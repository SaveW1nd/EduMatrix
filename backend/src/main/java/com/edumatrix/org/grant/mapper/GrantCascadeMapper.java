package com.edumatrix.org.grant.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 级联撤销的三条 SQL（02-数据库设计 §3.3.3「级联回收的实现思路（必须实现）」）。
 *
 * <h2>驱动顺序：先收敛到「该资源的全部授权行」，再 JOIN 子树</h2>
 * <p>§3.3.3 实现要点 1 逐字：先用 {@code idx_resource_type_id(resource_type, resource_id)}
 * 把候选行收敛到该资源的全部授权（单资源通常几十到几千行），<b>再</b> JOIN {@code org_node}
 * 做子树过滤。<b>反过来先扫子树会放大很多倍</b> —— 一个机构根的子树是全机构的人。
 *
 * <h2>子树条件的三个分支缺一不可</h2>
 * <pre>
 *   n.id = #{targetNodeId}                        ← 目标节点自身
 *   OR n.ancestors = #{prefix}                    ← 直接子节点（ancestors 恰好等于 P，后面没有逗号）
 *   OR n.ancestors LIKE CONCAT(#{prefix}, ',%')   ← 更深的后代
 * </pre>
 * <p><b>只写 LIKE 会漏掉整层直接子节点</b>；<b>LIKE 不以逗号收边</b>会让
 * {@code ...,100} 误命中 {@code ...,1001}（雪花 ID 虽等长，但平台根的 id 是 {@code 0}，
 * 长度并不齐）。这两条与 {@code OrgNodeSubtreeMapper#selectSubtreeIdsByPrefix} 同源。
 *
 * <h2>{@code deleted_at} 写<b>毫秒时间戳</b>，不写 1</h2>
 * <p>唯一索引 {@code uk_resource_target} <b>含 deleted_at</b>：若每次撤销都写同一个 1，
 * 「授→撤→再授→再撤」到第二次撤销就撞唯一键。表达式与
 * {@code BaseEntity} 上 {@code @TableLogic} 的 {@code delval} <b>逐字一致</b> ——
 * 注解 SQL 不受 {@code @TableLogic} 管，这里手写同一个表达式。
 *
 * <p><b>已知边界，如实写下来</b>：毫秒精度意味着「同一业务键在<b>同一毫秒内</b>被撤销两次」
 * 仍会撞键。那要求在 1ms 内完成「撤销→重新授权→再撤销」，实际做不到；
 * 且真撞上是 {@code Duplicate entry} —— <b>响亮失败，不是静默错误</b>，方向是对的。
 *
 * <h2>租户条件由插件注入</h2>
 * <p>契约 §2.9。02-数据库设计 §3.3.3 的示例 SQL 里手写了 {@code g.tenant_id = #{tenantId}}，
 * <b>本实现不照抄那一句</b>：本项目的插件对 UPDATE 同样生效，手写一份就有了两处真相。
 */
@Mapper
public interface GrantCascadeMapper {

    /** 子树内该资源的<b>全部有效</b>授权行数（含目标节点自身那一行）。 */
    @Select("SELECT COUNT(1) FROM org_resource_grant g "
            + "  JOIN org_node n ON n.id = g.target_node_id AND n.deleted_at = 0 "
            + " WHERE g.resource_type = #{resourceType} AND g.resource_id = #{resourceId} "
            + "   AND g.deleted_at = 0 "
            + "   AND (n.id = #{targetNodeId} OR n.ancestors = #{prefix} "
            + "        OR n.ancestors LIKE CONCAT(#{prefix}, ',%'))")
    int countSubtreeGrants(@Param("resourceType") int resourceType,
                           @Param("resourceId") Long resourceId,
                           @Param("targetNodeId") Long targetNodeId,
                           @Param("prefix") String prefix);

    /**
     * 被<b>级联</b>撤销的下级节点（不含目标节点自身），按 id 升序取前 {@code limit} 个。
     *
     * <p>{@code nodeType} 一并取回来 —— §9.3 的 {@code cascadeNodes[]} 要它，
     * 且调用方据此数「其中学员几名」（PRD FR-4 规则 6）。
     */
    @Select("SELECT n.id AS nodeId, n.node_name AS nodeName, n.node_type AS nodeType "
            + "  FROM org_resource_grant g "
            + "  JOIN org_node n ON n.id = g.target_node_id AND n.deleted_at = 0 "
            + " WHERE g.resource_type = #{resourceType} AND g.resource_id = #{resourceId} "
            + "   AND g.deleted_at = 0 AND n.id <> #{targetNodeId} "
            + "   AND (n.ancestors = #{prefix} OR n.ancestors LIKE CONCAT(#{prefix}, ',%')) "
            + " ORDER BY n.id LIMIT #{limit}")
    List<CascadeNodeRow> selectCascadeNodes(@Param("resourceType") int resourceType,
                                            @Param("resourceId") Long resourceId,
                                            @Param("targetNodeId") Long targetNodeId,
                                            @Param("prefix") String prefix,
                                            @Param("limit") int limit);

    /** 目标节点<b>自身</b>那一行在不在（0 或 1）—— {@code directRevokedCount} 用它。 */
    @Select("SELECT COUNT(1) FROM org_resource_grant "
            + " WHERE resource_type = #{resourceType} AND resource_id = #{resourceId} "
            + "   AND target_node_id = #{targetNodeId} AND deleted_at = 0")
    int countDirectGrant(@Param("resourceType") int resourceType,
                         @Param("resourceId") Long resourceId,
                         @Param("targetNodeId") Long targetNodeId);

    /**
     * 子树内<b>受影响的节点</b>（去重，含目标节点自身），一次问全部资源。
     *
     * <p>调用方据此算两个数：{@code affectedNodeCount}（跨全部目标<b>再</b>去重）
     * 与 {@code affectedStudentCount}（其中 {@code node_type = 3} 的）。
     *
     * <h2>为什么不从 {@link #selectCascadeNodes} 的样本里数</h2>
     * <p>那个样本<b>封顶 50 个</b>。从样本里数出来的「其中学员 M 名」
     * 在影响面超过 50 时<b>恒等于错的</b>，而它照样会显示在撤销确认弹窗上
     *（PRD FR-4 规则 6 要求那三个数字<b>准确</b>）—— 接口 200、字段齐全、数字错。
     *
     * <h2>为什么不用 COUNT(DISTINCT) 逐目标相加</h2>
     * <p>目标节点之间<b>可能嵌套</b>（同时撤 A1 与 A1 名下的 T1 是合法请求），
     * 那时同一个节点会被数两次。返回 ID 让调用方做<b>集合并</b>，才是精确的。
     */
    @Select("<script>"
            + "SELECT DISTINCT n.id AS nodeId, n.node_type AS nodeType "
            + "  FROM org_resource_grant g "
            + "  JOIN org_node n ON n.id = g.target_node_id AND n.deleted_at = 0 "
            + " WHERE g.resource_type = #{resourceType} AND g.deleted_at = 0 "
            + "   AND g.resource_id IN "
            + "<foreach collection='resourceIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach>"
            + "   AND (n.id = #{targetNodeId} OR n.ancestors = #{prefix} "
            + "        OR n.ancestors LIKE CONCAT(#{prefix}, ',%'))"
            + "</script>")
    List<AffectedNodeRow> selectAffectedNodes(@Param("resourceType") int resourceType,
                                              @Param("resourceIds") List<Long> resourceIds,
                                              @Param("targetNodeId") Long targetNodeId,
                                              @Param("prefix") String prefix);

    /**
     * <b>一条 UPDATE 撤销「目标节点 + 其整棵子树」内该资源的全部有效授权</b>。
     *
     * <p>WHERE 与 {@link #countSubtreeGrants} <b>逐字相同</b> —— 调用方拿两者的结果比对，
     * 不一致即说明有并发写入，整个事务回滚。两条的条件一旦分叉，
     * 那个比对就变成了永远成立的空话。
     *
     * @return 实际影响行数
     */
    @Update("UPDATE org_resource_grant g "
            + "  JOIN org_node n ON n.id = g.target_node_id AND n.deleted_at = 0 "
            + "   SET g.deleted_at = UNIX_TIMESTAMP(NOW(3))*1000, "
            + "       g.update_by = #{operatorId}, g.update_time = NOW(), "
            + "       g.remark = #{reason} "
            + " WHERE g.resource_type = #{resourceType} AND g.resource_id = #{resourceId} "
            + "   AND g.deleted_at = 0 "
            + "   AND (n.id = #{targetNodeId} OR n.ancestors = #{prefix} "
            + "        OR n.ancestors LIKE CONCAT(#{prefix}, ',%'))")
    int revokeSubtree(@Param("resourceType") int resourceType,
                      @Param("resourceId") Long resourceId,
                      @Param("targetNodeId") Long targetNodeId,
                      @Param("prefix") String prefix,
                      @Param("operatorId") Long operatorId,
                      @Param("reason") String reason);

    /** {@link #selectAffectedNodes} 的行。 */
    class AffectedNodeRow {
        private Long nodeId;
        private Integer nodeType;

        public Long getNodeId() {
            return nodeId;
        }

        public void setNodeId(Long nodeId) {
            this.nodeId = nodeId;
        }

        public Integer getNodeType() {
            return nodeType;
        }

        public void setNodeType(Integer nodeType) {
            this.nodeType = nodeType;
        }
    }

    /** {@link #selectCascadeNodes} 的行。 */
    class CascadeNodeRow {
        private Long nodeId;
        private String nodeName;
        private Integer nodeType;

        public Long getNodeId() {
            return nodeId;
        }

        public void setNodeId(Long nodeId) {
            this.nodeId = nodeId;
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
    }
}
