package com.edumatrix.org.node.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code org_resource_grant} 的<b>两条窄只读</b>：移动响应里的 {@code outOfScopeGrants}
 * （契约 §2.5 规则 9、03-02 §3.4），与节点详情里的 {@code grantedResourceStat}（§3.2）。
 *
 * <h2>⚠ 临时构件，交接给模块 11 的 {@code org/grant}</h2>
 * <p>登记在 {@code com.edumatrix.org.node} 的 {@code package-info}。
 *
 * <h2>只读，一个字都不写</h2>
 * <p>04-实施计划.md 模块 06 的「涉及表」把 {@code org_resource_grant} 列在<b>只读</b>栏，
 * 规则 8 同时写着「<b>本模块先把字段与开关做出来</b>，级联回收动作在模块 11 接上」。
 * 所以 {@code revokeOutOfScopeGrants=true} 在本模块<b>不执行回收</b> ——
 * 处置见 {@code NodeMoveService} 里那段注释。
 *
 * <h2>判定口径：用 §3.4 自己的措辞，而不是契约那句完整判据</h2>
 * <p>契约 §2.5 规则 9 的完整判据是「授权行的 {@code target_node_id} 当前祖先链<b>不再包含</b>
 * 该资源 {@code owner_node_id} <b>或其有效授权链</b>」。那需要读
 * {@code crs_course} / {@code qb_question} / {@code vod_video} 的 {@code owner_node_id}
 * 并对授权链做递归 —— 那三张表是模块 08/09/10 的涉及表，<b>不在模块 06 的涉及表里</b>。
 *
 * <p>本模块按 §3.4 的措辞实现可算的那一半：「<b>由原上级授予</b>、现已跨出其管辖范围的授权」
 * —— 即<b>授权人所在节点</b>（{@code grant_by} → {@code sys_user.node_id}）
 * 在移动后<b>不再是</b>目标节点的祖先、也不是目标节点自身。
 *
 * <p><b>模块 11 必须回头补齐另一半</b>：owner 侧的判定，以及 {@code resourceName}
 * （本模块返回 {@code null}，理由同上 —— 资源名在那三张表里）。
 */
@Mapper
public interface NodeGrantScopeMapper {

    /**
     * 被移动子树内、由「移动后已不在其祖先链上的人」授予的授权行。
     *
     * <p><b>祖先链判定放在 Java 侧</b>：SQL 里判「A 是不是 B 的祖先」只有
     * {@code FIND_IN_SET} 一种写法，而契约 §7.1 写死它出现在慢查询日志中即视为缺陷
     * （检查②会 grep）。这里改为：把子树内每一行的
     * {@code (targetNodeId, targetAncestors, granterNodeId)} 取出来，
     * 由调用方用字符串拆分判定 —— 与 {@code common/subtree/NodePath#parseAncestorIds}
     * 的做法一致。
     *
     * <p><b>必须在步骤 5 之后、同一事务内调用</b>：判定依据是<b>移动之后</b>的
     * {@code ancestors}，而重算就在步骤 5。同事务内读得到自己尚未提交的写入，
     * 所以<b>不必等提交</b> —— 也<b>不能</b>等：{@code outOfScopeGrants} 要进本次响应，
     * 而提交发生在响应之后。
     *
     * <p>（这与「清 {@code node:anc:*} 必须等提交」<b>不矛盾</b>：那件事要让
     * <b>别的连接</b>看到新树，本条只要<b>自己</b>看到。）
     *
     * <h2>有效期口径与 {@link #selectGrantedResourceStat} <b>逐字相同</b></h2>
     * <p>两端都判、两端都放行 {@code NULL}：{@code valid_start IS NULL} 表示立即生效、
     * {@code valid_end IS NULL} 表示永久有效（DDL 列注释）。
     *
     * <p><b>⚠ 上界是 {@code valid_end >= NOW()}，不是 {@code >}（模块 11 收敛，D7）</b>。
     * 本模块交付时这两条写的是 {@code >}，而全库唯一口径
     *（{@code common/grant/mapper/ResourceGrantMapper.VALID_NOW}、02-数据库设计 §3.3.2
     * 的鉴权 SQL、DDL 列注释）都是 {@code >=}。两者<b>只在到期那一秒结论相反</b>，
     * 后果是：节点移动算出的「跨管辖集」与授权引擎算出的「有效集」差一行 ——
     * <b>移动响应说没有跨管辖授权，实际有一条卡在边界上</b>，而两边都返回 200。
     * 由 {@code GrantValidityBoundaryIT} 用一条 {@code valid_end} 恰好等于当前秒的
     * 授权行钉住「两条路径结论相同」。
     *
     * <p><b>漏判 {@code valid_start} 的后果</b>：一条<b>尚未生效</b>的未来授权会被算进
     * {@code outOfScopeGrantCount} 并出现在清单里 —— 操作者看到一条「现在根本还用不了」的授权
     * 被列为待办，而接口返回 200、没有任何报错。同一个 Mapper 里两条查询用两套有效期口径，
     * 是这类偏差最容易长出来的地方，所以<b>这两条必须一起改</b>。
     *
     * @param movingNodeId 被移动节点（它自己持有的授权也要算）
     * @param prefix       移动<b>之后</b>被移动节点的自身路径前缀
     */
    @Select("SELECT g.resource_type AS resourceType, g.resource_id AS resourceId, "
            + "       g.target_node_id AS targetNodeId, n.node_name AS targetNodeName, "
            + "       n.ancestors AS targetAncestors, u.node_id AS granterNodeId "
            + "  FROM org_resource_grant g "
            + "  JOIN org_node n ON n.id = g.target_node_id AND n.deleted_at = 0 "
            + "  LEFT JOIN sys_user u ON u.id = g.grant_by AND u.deleted_at = 0 "
            + " WHERE g.deleted_at = 0 "
            + "   AND (g.valid_start IS NULL OR g.valid_start <= NOW()) "
            + "   AND (g.valid_end IS NULL OR g.valid_end >= NOW()) "
            + "   AND (n.id = #{movingNodeId} "
            + "        OR n.ancestors = #{prefix} OR n.ancestors LIKE CONCAT(#{prefix}, ',%'))")
    List<GrantScopeRow> selectSubtreeGrants(@Param("movingNodeId") Long movingNodeId,
                                            @Param("prefix") String prefix);

    /**
     * §3.2 的 {@code grantedResourceStat}：本节点<b>已获授权且在有效期内</b>的资源数，
     * 按 {@code resource_type} 分组。
     *
     * <p><b>只看授权给本节点的行，不回溯祖先链</b> —— 契约 §2.5 规则 3/4：
     * 「不向下继承，每一层都必须显式授权」「判定不回溯祖先链」。
     * 字段说明也逐字写着「不含其祖先持有但未下发给本节点的资源」。
     *
     * <p>{@code valid_start} 为 {@code NULL} 表示立即生效，{@code valid_end} 为
     * {@code NULL} 表示永久有效（DDL 列注释），两端都要放行 {@code NULL}。
     * 命中 {@code idx_target_resource} 的最左前缀。
     */
    @Select("SELECT resource_type AS resourceType, COUNT(1) AS cnt FROM org_resource_grant "
            + " WHERE target_node_id = #{nodeId} AND deleted_at = 0 "
            + "   AND (valid_start IS NULL OR valid_start <= NOW()) "
            + "   AND (valid_end IS NULL OR valid_end >= NOW()) "
            + " GROUP BY resource_type")
    List<ResourceTypeCountRow> selectGrantedResourceStat(@Param("nodeId") Long nodeId);

    /** {@code resource_type → 数量} 的窄投影。 */
    class ResourceTypeCountRow {
        private Integer resourceType;
        private Long cnt;

        public Integer getResourceType() {
            return resourceType;
        }

        public void setResourceType(Integer resourceType) {
            this.resourceType = resourceType;
        }

        public Long getCnt() {
            return cnt;
        }

        public void setCnt(Long cnt) {
            this.cnt = cnt;
        }
    }

    /** {@link #selectSubtreeGrants} 的行。 */
    class GrantScopeRow {
        private Integer resourceType;
        private Long resourceId;
        private Long targetNodeId;
        private String targetNodeName;
        /** 目标节点<b>移动之后</b>的祖级路径，供 Java 侧判「授权人还在不在链上」。 */
        private String targetAncestors;
        /** 授权人所在节点；账号已删除时为 {@code null}（LEFT JOIN）。 */
        private Long granterNodeId;

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

        public Long getGranterNodeId() {
            return granterNodeId;
        }

        public void setGranterNodeId(Long granterNodeId) {
            this.granterNodeId = granterNodeId;
        }
    }
}
