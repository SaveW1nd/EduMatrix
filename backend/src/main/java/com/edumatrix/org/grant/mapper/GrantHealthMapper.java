package com.edumatrix.org.grant.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 授权健康度巡检的三条查询（契约 §2.5 规则 6、02-数据库设计 §3.3.4、03-02 §9.6）。
 *
 * <h2>⚠ 巡检任务与接口 51 调的是<b>同一批方法</b>（F-83）</h2>
 * <p>需方定案：巡检结果<b>不落快照</b>，接口实时算。若接口另写一份 SQL，
 * 就会出现<b>「巡检说健康、页面说有问题」（或反过来）</b>，
 * 而那时<b>无法判断哪个是对的</b> —— 两份实现都返回 200。
 *
 * <h2>候选行的判据用「<b>父节点</b>」，与 {@code canRegrant} 的「整条链」是两回事</h2>
 * <p>契约 §2.5 规则 6 与 02 §3.3.4 的 SQL 都写的是<b>父级</b>：
 * 「父级已无权、子级仍持有」。这与 {@code ResourceOwnerChecker.canRegrant} 的
 * 整条链判定<b>不冲突，且粒度更合用</b>：
 * <ul>
 *   <li>链在 A1 断了 → 只有 <b>A1 的子级</b>（其父恰好是 A1）被标出来，
 *       {@code missingNodeId} 正是 A1 —— 页面上「补授上级」<b>要补的就是它</b>；
 *   <li>若改用整条链判定，A1 以下<b>每一层</b>都会被标出来，
 *       运营看到的是一片红而不是一个断点，处置动作反而找不到落点。
 * </ul>
 *
 * <h2>owner 的判定<b>不在 SQL 里</b></h2>
 * <p>02 §3.3.4 的示例 SQL 直接 {@code NOT EXISTS (SELECT 1 FROM crs_course ...)}，
 * 三类资源要写三份（它自己也说「题目/视频同构，可 UNION ALL 或分三次跑」）。
 * <b>本实现只查「父节点有没有有效授权行」，owner 那一半交给
 * {@code ResourceOwnerProvider.ownerNodeIdsOf} 在 Java 侧批量判</b>：
 * <ol>
 *   <li>三类资源共用<b>一条</b> SQL，不写三份；
 *   <li>{@code org} 域不必在 SQL 里认识 {@code crs_} / {@code qb_} / {@code vod_} 三张表 ——
 *       与检查③ 的分层意图一致（那条检查拦的是 import，但绕开它不等于该绕）。
 * </ol>
 *
 * <p><b>租户条件由插件注入</b>（契约 §2.9）。巡检任务逐租户 {@code runWithTenant} 进入，
 * <b>不开跨租户逃生舱</b>。
 */
@Mapper
public interface GrantHealthMapper {

    /**
     * 候选行：<b>父节点对该资源没有有效授权行</b>的那些授权（{@code parent_id > 0}）。
     *
     * <p>还要在 Java 侧去掉「父节点恰好是该资源 owner」的那些 —— 那不是悬挂。
     *
     * <p>{@code parent_id > 0} 把机构根排除在外：机构根之上是平台根（{@code id = 0}），
     * 平台不参与租户内授权，机构根的授权行<b>永远查不到「上级」</b>，
     * 不排除的话每个机构的根节点授权都会被报成悬挂。
     */
    @Select("SELECT g.id, g.resource_type AS resourceType, g.resource_id AS resourceId, "
            + "       g.target_node_id AS targetNodeId, n.node_name AS targetNodeName, "
            + "       n.parent_id AS parentNodeId, p.node_name AS parentNodeName, "
            + "       n.ancestors AS targetAncestors, g.valid_end AS validEnd, "
            + "       g.grant_time AS grantTime "
            + "  FROM org_resource_grant g "
            + "  JOIN org_node n ON n.id = g.target_node_id AND n.deleted_at = 0 "
            + "  JOIN org_node p ON p.id = n.parent_id AND p.deleted_at = 0 "
            + " WHERE g.deleted_at = 0 AND n.parent_id > 0 "
            + "   AND (g.valid_start IS NULL OR g.valid_start <= NOW()) "
            + "   AND (g.valid_end IS NULL OR g.valid_end >= NOW()) "
            + "   AND NOT EXISTS (SELECT 1 FROM org_resource_grant pg "
            + "                    WHERE pg.resource_type = g.resource_type "
            + "                      AND pg.resource_id = g.resource_id "
            + "                      AND pg.target_node_id = n.parent_id "
            + "                      AND pg.deleted_at = 0 "
            + "                      AND (pg.valid_start IS NULL OR pg.valid_start <= NOW()) "
            + "                      AND (pg.valid_end IS NULL OR pg.valid_end >= NOW())) "
            + " ORDER BY g.id")
    List<HealthRow> selectSuspects();

    /**
     * 30 天内到期的授权（{@code type=expiring}，PRD FR-3 规则 6 的临期提醒）。
     *
     * <p>永久有效（{@code valid_end IS NULL}）的不算临期 —— 它没有「期」。
     */
    @Select("SELECT g.id, g.resource_type AS resourceType, g.resource_id AS resourceId, "
            + "       g.target_node_id AS targetNodeId, n.node_name AS targetNodeName, "
            + "       n.parent_id AS parentNodeId, NULL AS parentNodeName, "
            + "       n.ancestors AS targetAncestors, g.valid_end AS validEnd, "
            + "       g.grant_time AS grantTime "
            + "  FROM org_resource_grant g "
            + "  JOIN org_node n ON n.id = g.target_node_id AND n.deleted_at = 0 "
            + " WHERE g.deleted_at = 0 AND g.valid_end IS NOT NULL "
            + "   AND g.valid_end >= NOW() "
            + "   AND g.valid_end <= DATE_ADD(NOW(), INTERVAL #{days} DAY) "
            + " ORDER BY g.valid_end")
    List<HealthRow> selectExpiring(@Param("days") int days);

    /**
     * 每个节点当前持有的<b>有效</b>授权行数 —— 契约 §7.1 的
     * {@code grant_rows_per_node}（Histogram，P99 &gt; 2000 告警）。
     *
     * <p><b>盯单节点持有量而不是表总量</b>：点查的扫描行数只由单节点持有量决定，
     * 且该上界由人均持有量决定、<b>不随机构人数变化</b>。
     */
    @Select("SELECT target_node_id AS nodeId, COUNT(1) AS rows_ FROM org_resource_grant "
            + " WHERE deleted_at = 0 "
            + "   AND (valid_start IS NULL OR valid_start <= NOW()) "
            + "   AND (valid_end IS NULL OR valid_end >= NOW()) "
            + " GROUP BY target_node_id")
    List<NodeRowCount> selectRowsPerNode();

    /**
     * 这些节点在<b>各自的 {@code since} 之后</b>有没有发生过「移动」类异动 ——
     * F-82 区分 {@code dangling} 与 {@code crossScope} 的判据。
     *
     * <p>{@code change_type} 取 <b>2 分配导师 / 3 转交管理员 / 4 教师调岗 / 8 节点移动</b>
     *（契约 §5 {@code change_type}）—— 这四种是「换了上级」，
     * 而 1 建档 / 5 归档 / 6 恢复 / 7 退课不改变管辖关系。
     *
     * @return 有过移动的节点 ID（去重）
     */
    @Select("<script>"
            + "SELECT DISTINCT node_id FROM org_node_change_log "
            + " WHERE change_type IN (2, 3, 4, 8) AND change_time >= #{since} "
            + "   AND node_id IN "
            + "<foreach collection='nodeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> selectMovedNodeIds(@Param("nodeIds") List<Long> nodeIds,
                                  @Param("since") java.time.LocalDateTime since);

    /** 巡检 / 接口 51 的一行。 */
    class HealthRow {
        private Long id;
        private Integer resourceType;
        private Long resourceId;
        private Long targetNodeId;
        private String targetNodeName;
        private Long parentNodeId;
        private String parentNodeName;
        private String targetAncestors;
        private java.time.LocalDateTime validEnd;
        private java.time.LocalDateTime grantTime;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Integer getResourceType() {
            return resourceType;
        }

        public void setResourceType(Integer resourceType) {
            this.resourceType = resourceType;
        }

        public Long getResourceId() {
            return resourceId;
        }

        public void setResourceId(Long resourceId) {
            this.resourceId = resourceId;
        }

        public Long getTargetNodeId() {
            return targetNodeId;
        }

        public void setTargetNodeId(Long targetNodeId) {
            this.targetNodeId = targetNodeId;
        }

        public String getTargetNodeName() {
            return targetNodeName;
        }

        public void setTargetNodeName(String targetNodeName) {
            this.targetNodeName = targetNodeName;
        }

        public Long getParentNodeId() {
            return parentNodeId;
        }

        public void setParentNodeId(Long parentNodeId) {
            this.parentNodeId = parentNodeId;
        }

        public String getParentNodeName() {
            return parentNodeName;
        }

        public void setParentNodeName(String parentNodeName) {
            this.parentNodeName = parentNodeName;
        }

        public String getTargetAncestors() {
            return targetAncestors;
        }

        public void setTargetAncestors(String targetAncestors) {
            this.targetAncestors = targetAncestors;
        }

        public java.time.LocalDateTime getValidEnd() {
            return validEnd;
        }

        public void setValidEnd(java.time.LocalDateTime validEnd) {
            this.validEnd = validEnd;
        }

        public java.time.LocalDateTime getGrantTime() {
            return grantTime;
        }

        public void setGrantTime(java.time.LocalDateTime grantTime) {
            this.grantTime = grantTime;
        }
    }

    /** {@link #selectRowsPerNode} 的行。 */
    class NodeRowCount {
        private Long nodeId;
        private Integer rows_;

        public Long getNodeId() {
            return nodeId;
        }

        public void setNodeId(Long nodeId) {
            this.nodeId = nodeId;
        }

        public Integer getRows_() {
            return rows_;
        }

        public void setRows_(Integer rows) {
            this.rows_ = rows;
        }
    }
}
