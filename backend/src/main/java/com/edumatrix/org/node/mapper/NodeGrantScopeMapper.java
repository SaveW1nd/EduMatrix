package com.edumatrix.org.node.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code org_resource_grant} 的<b>一条窄只读</b>：节点详情里的
 * {@code grantedResourceStat}（03-02 §3.2）。
 *
 * <h2>⚠ 另一条已交接给模块 11（本模块的 {@code package-info} 登记的那件事）</h2>
 * <p>原先还有一条 {@code selectSubtreeGrants}，算移动响应的 {@code outOfScopeGrants}。
 * 它当时只能实现<b>可算的那一半</b>——按 §3.4 的措辞判「由原上级授予」
 *（{@code grant_by} 所在节点移动后不再是祖先），
 * 因为契约 §2.5 规则 9 的完整判据要读 {@code crs_course} / {@code qb_question} /
 * {@code vod_video} 的 {@code owner_node_id}，<b>那三张表不在本模块的涉及表里</b>。
 *
 * <p>模块 11 落地后已连同判定一起搬到
 * {@code org/grant/mapper/OutOfScopeGrantMapper} + {@code org/grant/service/OutOfScopeGrantResolver}，
 * 判定<b>直接复用</b> {@code ResourceOwnerChecker.canRegrant} —— 不新写一份。
 * {@code NodeMoveService} 改注入那个 Resolver。
 *
 * <h2>剩下这条是常驻的，不是临时构件</h2>
 * <p>{@code grantedResourceStat} 是节点详情自己的展示字段，与授权引擎无关，留在本模块。
 *
 * <p><b>租户条件由插件注入</b>（契约 §2.9）。
 */
@Mapper
public interface NodeGrantScopeMapper {

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

}
