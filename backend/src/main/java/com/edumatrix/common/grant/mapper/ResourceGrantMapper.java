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
 * <p>四条查询全部命中 {@code idx_target_resource
 * (target_node_id, resource_type, resource_id, deleted_at, valid_end)}
 * 或 {@code idx_resource_type_id (resource_type, resource_id)}，无一处 {@code FIND_IN_SET}。
 *
 * <p><b>租户条件由插件注入</b>，这里一个字都不写（契约 §2.9）。
 */
@Mapper
public interface ResourceGrantMapper {

    /**
     * 有效期谓词。<b>四条查询共用同一段文本</b>，防止「有的地方判了 valid_start、有的地方没判」。
     *
     * <p><b>{@code valid_end >= NOW()} 是全库唯一口径</b>（02-数据库设计 §3.3.2 的 SQL
     * 与 DDL 列注释都是这个）。曾经有第二份写成 {@code valid_end > NOW()}
     * （{@code org/node/mapper/NodeGrantScopeMapper}，模块 06 交付）——
     * 两者<b>只在到期那一秒结论相反</b>，表现是节点移动响应说「没有跨管辖授权」
     * 而实际有一条卡在边界上，而两边都返回 200。模块 11 已把那两条收敛到本常量的口径，
     * 并由 {@code GrantValidityBoundaryIT} 用一条 {@code valid_end} 恰好等于当前秒的
     * 授权行钉住「两条路径结论相同」。
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

    /**
     * 「这些节点里，谁当前有效持有这些资源」—— 契约 §2.5 规则 9 的链判定用它。
     *
     * <h2>⚠ 这条<b>会</b>拿一串祖先节点来查，但它不是规则 4 禁的那种回溯</h2>
     * <p>规则 4 禁的是<b>可用性</b>判定回溯祖先链（「我能不能用资源 X」只看
     * {@code target_node_id = 我} 一条命中）。而规则 9 的判据<b>本身就是链判定</b>：
     * 「授权行的 {@code target_node_id} 当前祖先链<b>不再包含</b>该资源
     * {@code owner_node_id} 或其有效授权链时，该行只读」——
     * 判「链断没断」不看链是判不出来的。
     *
     * <p>两条规则管的是两件事，<b>看起来像冲突而不是冲突</b>：
     * <table border="1">
     *   <caption>回溯与否，按问题分</caption>
     *   <tr><th>问的问题</th><th>回溯祖先链？</th><th>依据</th><th>入口</th></tr>
     *   <tr><td>我能不能<b>用</b>资源 X</td><td><b>否</b>，单条命中</td><td>契约 §2.5 规则 4</td>
     *       <td>{@link #countActiveGrant}</td></tr>
     *   <tr><td>我能不能<b>再下发</b>资源 X</td><td><b>是</b>，一次批量</td><td>契约 §2.5 规则 9</td>
     *       <td>本方法</td></tr>
     * </table>
     * <p><b>删掉本方法「以合规」会让规则 9 整条失去落地</b>，而那不会报错 ——
     * 表现是调岗的教师照样能把原校区的课授给新校区的学员（契约 §2.5 规则 9
     * 逐字描述的资产穿透）。
     *
     * <h2>为什么是一条批量查询而不是逐个点查</h2>
     * <p>树深上限 50 级（契约 §2.3 结构约束 5），接口 38 单次 500 个资源 ——
     * 逐个点查是 500 × 50 次往返。一条 {@code IN × IN} 一次拿完，
     * 命中 {@code idx_resource_type_id (resource_type, resource_id)}。
     *
     * @param nodeIds 候选节点（调用方传的是「我」的祖先链）；<b>不含「我」自己</b>时
     *                也完全正常 —— 本方法只回答事实，不做任何判定
     */
    @Select("<script>"
            + "SELECT resource_id AS resourceId, target_node_id AS targetNodeId "
            + "  FROM org_resource_grant "
            + " WHERE resource_type = #{resourceType} "
            + "   AND resource_id IN "
            + "   <foreach collection='resourceIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach>"
            + "   AND target_node_id IN "
            + "   <foreach collection='nodeIds' item='nid' open='(' separator=',' close=')'>#{nid}</foreach>"
            + VALID_NOW
            + "</script>")
    List<GrantHolderRow> selectGrantHolders(@Param("resourceType") int resourceType,
                                            @Param("resourceIds") List<Long> resourceIds,
                                            @Param("nodeIds") List<Long> nodeIds);

    /** {@link #selectGrantHolders} 的行。 */
    class GrantHolderRow {
        private Long resourceId;
        private Long targetNodeId;

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
    }

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
