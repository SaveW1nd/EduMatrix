package com.edumatrix.org.grant.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 被移动子树内的授权行 —— <b>接管自模块 06 的 {@code org/node/mapper/NodeGrantScopeMapper}</b>。
 *
 * <h2>为什么搬到 {@code org/grant}</h2>
 * <p>模块 06 的 {@code package-info} 把它登记为「未到期，交模块 11」的临时构件：
 * 那时只能算出<b>可算的那一半</b>（「由原上级授予」= {@code grant_by} 所在节点不再是祖先），
 * 因为契约 §2.5 规则 9 的完整判据要读 {@code crs_course} / {@code qb_question} /
 * {@code vod_video} 的 {@code owner_node_id} 并对授权链做判定 ——
 * <b>那三张表不在模块 06 的涉及表里</b>。
 *
 * <p>本模块有那半判定（{@code ResourceOwnerChecker.canRegrant}），于是查询搬过来、
 * 判定交给调用方。{@code NodeGrantScopeMapper} 只剩节点详情的
 * {@code grantedResourceStat} 一条。
 *
 * <h2>判定不在 SQL 里做</h2>
 * <p>SQL 只负责「把被移动子树内的授权行连同它们移动<b>之后</b>的 {@code ancestors} 取出来」。
 * 「这一行算不算跨管辖」由 {@code OutOfScopeGrantResolver} 用
 * {@code canRegrant} 判 —— 那是<b>全系统唯一口径</b>，在 SQL 里再写一遍就是第二份实现。
 *
 * <p><b>祖先链判定绝不进 SQL</b>：判「A 是不是 B 的祖先」只有 {@code FIND_IN_SET} 一种写法，
 * 而契约 §7.1 写死它出现在慢查询日志中即视为缺陷（约定检查② 会 grep）。
 *
 * <p><b>不判有效期</b>（需方 2026-08-21 定案：授权一律永久有效），
 * 与 {@code ResourceGrantMapper.NOT_DELETED} 同口径
 *（D7 已收敛，见那里的注释）。
 */
@Mapper
public interface OutOfScopeGrantMapper {

    /**
     * 被移动子树内当前<b>有效</b>的全部授权行（含被移动节点自身持有的）。
     *
     * <p><b>必须在 {@code ancestors} 重算之后、同一事务内调用</b>：判定依据是
     * <b>移动之后</b>的树形。同事务内读得到自己尚未提交的写入，所以不必等提交 ——
     * 也<b>不能</b>等：清单要进本次响应，而提交发生在响应之后。
     *
     * @param movingNodeId 被移动节点（它自己持有的授权也要算）
     * @param prefix       移动<b>之后</b>被移动节点的自身路径前缀
     */
    @Select("SELECT g.resource_type AS resourceType, g.resource_id AS resourceId, "
            + "       g.target_node_id AS targetNodeId, n.node_name AS targetNodeName, "
            + "       n.ancestors AS targetAncestors "
            + "  FROM org_resource_grant g "
            + "  JOIN org_node n ON n.id = g.target_node_id AND n.deleted_at = 0 "
            + " WHERE g.deleted_at = 0 "
            + "   AND (n.id = #{movingNodeId} "
            + "        OR n.ancestors = #{prefix} OR n.ancestors LIKE CONCAT(#{prefix}, ',%'))")
    List<SubtreeGrantRow> selectSubtreeGrants(@Param("movingNodeId") Long movingNodeId,
                                              @Param("prefix") String prefix);

    /** {@link #selectSubtreeGrants} 的行。 */
    class SubtreeGrantRow {
        private Integer resourceType;
        private Long resourceId;
        private Long targetNodeId;
        private String targetNodeName;

        /** 目标节点<b>移动之后</b>的祖级路径 —— 级联回收要用它拼子树前缀。 */
        private String targetAncestors;

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

        public String getTargetAncestors() {
            return targetAncestors;
        }

        public void setTargetAncestors(String targetAncestors) {
            this.targetAncestors = targetAncestors;
        }
    }
}
