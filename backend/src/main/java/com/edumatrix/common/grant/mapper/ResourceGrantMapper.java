package com.edumatrix.common.grant.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code org_resource_grant} 的<b>只读</b>窄 Mapper —— 公共层自用，业务模块不要注入它。
 *
 * <p>与 {@code common/subtree/mapper/OrgNodeSubtreeMapper} 同型：公共层直读别的领域的表，
 * 前提是它承载的是全系统唯一口径。业务侧一律走
 * {@code common/grant/ResourceGrantReader}；授权的<b>写侧</b>（授权 / 撤销 / 级联回收 /
 * 有效期截断）是模块 11 的 {@code org/grant/}，<b>不在本类里</b>。
 *
 * <p><b>本类永远只读</b>：出现 {@code @Insert} / {@code @Update} / {@code @Delete}
 * 即为把模块 11 的写侧偷渡进公共层。这与 {@code system/log/mapper} 的只读约束
 * （约定检查⑥）是同一件事，只是那一处已被脚本守住、这一处靠本注释与代码评审。
 *
 * <p>三条查询全部命中 {@code idx_target_resource
 * (target_node_id, resource_type, resource_id, deleted_at, valid_end)}
 * 或 {@code idx_resource_type_id (resource_type, resource_id)}，无一处 {@code FIND_IN_SET}。
 *
 * <p><b>租户条件由插件注入</b>，这里一个字都不写（契约 §2.9）。
 */
@Mapper
public interface ResourceGrantMapper {

    /**
     * 有效期谓词。三条查询共用同一段文本，防止「有的地方判了 valid_start、有的地方没判」。
     *
     * <p><b>刻意写成 {@code NOW() >= valid_start} 而不是 {@code valid_start <= NOW()}</b>：
     * 本常量会被拼进 {@code <script>} 形式的注解 SQL，那种写法要按 XML 解析，
     * 裸的 {@code <} 会让 MyBatis 在<b>启动时</b>抛
     * {@code SAXParseException: 元素内容必须由格式正确的字符数据或标记组成}。
     * 换成 {@code &lt;} 又会让非 {@code <script>} 的那两条把转义符原样发给 MySQL。
     * 把不等号掉个个儿，两种形式都不用特殊处理。
     */
    String VALID_NOW = " AND deleted_at = 0 "
            + " AND (valid_start IS NULL OR NOW() >= valid_start) "
            + " AND (valid_end IS NULL OR valid_end >= NOW()) ";

    /**
     * 点查：该节点对该资源是否有有效授权。<b>{@code target_node_id} 精确相等，不回溯祖先链。</b>
     */
    @Select("SELECT COUNT(*) FROM org_resource_grant "
            + " WHERE resource_type = #{resourceType} AND resource_id = #{resourceId} "
            + "   AND target_node_id = #{nodeId} " + VALID_NOW)
    int countActiveGrant(@Param("resourceType") int resourceType,
                         @Param("resourceId") Long resourceId,
                         @Param("nodeId") Long nodeId);

    /** 列表：该节点被有效授权的全部资源 ID。 */
    @Select("SELECT DISTINCT resource_id FROM org_resource_grant "
            + " WHERE resource_type = #{resourceType} AND target_node_id = #{nodeId} " + VALID_NOW)
    List<Long> selectActiveResourceIds(@Param("resourceType") int resourceType,
                                       @Param("nodeId") Long nodeId);

    /** 按资源统计当前有效授权的目标节点数（03-03 §1.1 的 {@code grantedNodeCount}）。 */
    @Select("<script>"
            + "SELECT resource_id AS resourceId, COUNT(DISTINCT target_node_id) AS targetCount "
            + "  FROM org_resource_grant "
            + " WHERE resource_type = #{resourceType} "
            + "   AND resource_id IN "
            + "   <foreach collection='resourceIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach>"
            + VALID_NOW
            + " GROUP BY resource_id"
            + "</script>")
    List<GrantCountRow> countActiveTargets(@Param("resourceType") int resourceType,
                                           @Param("resourceIds") List<Long> resourceIds);

    /** {@link #countActiveTargets} 的行。 */
    class GrantCountRow {
        private Long resourceId;
        private Integer targetCount;

        public Long getResourceId() {
            return resourceId;
        }

        public void setResourceId(Long resourceId) {
            this.resourceId = resourceId;
        }

        public Integer getTargetCount() {
            return targetCount;
        }

        public void setTargetCount(Integer targetCount) {
            this.targetCount = targetCount;
        }
    }
}
