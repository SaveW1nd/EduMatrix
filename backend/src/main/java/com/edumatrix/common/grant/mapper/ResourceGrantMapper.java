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
 * {@code common/grant/ResourceGrantReader}；授权的<b>写侧</b>（授权 / 撤销 / 级联回收）
 * 是模块 11 的 {@code org/grant/}，<b>不在本类里</b>。
 *
 * <p><b>本类永远只读</b>：出现 {@code @Insert} / {@code @Update} / {@code @Delete}
 * 即为把模块 11 的写侧偷渡进公共层。这与 {@code system/log/mapper} 的只读约束
 * （约定检查⑥）是同一件事，只是那一处已被脚本守住、这一处靠本注释与代码评审。
 *
 * <p>四条查询全部命中 {@code idx_target_resource
 * (target_node_id, resource_type, resource_id, deleted_at, valid_end)}
 * 或 {@code idx_resource_type_id (resource_type, resource_id)}，无一处 {@code FIND_IN_SET}。
 * 索引末列 {@code valid_end} 现已用不上（谓词只剩软删），<b>但索引不动</b> ——
 * 前四列仍是精确匹配的最左前缀，多一个尾列不影响选择性。
 *
 * <p><b>租户条件由插件注入</b>，这里一个字都不写（契约 §2.9）。
 */
@Mapper
public interface ResourceGrantMapper {

    /**
     * 行有效性谓词。<b>四条查询共用同一段文本</b>，防止「有的地方判了、有的地方没判」。
     *
     * <h2>⚠ 这里曾经还判有效期，现在<b>只判软删</b>（需方 2026-08-21 定案）</h2>
     * <p>定案原话：「课程权限我不希望有时间，我觉得不需要有时间，这还不如直接收回权限」。
     * <b>授权没有有效期</b>，失效手段只有两个 —— 显式撤销（接口 39，级联）与学籍状态
     *（播放凭证校验链第一步「学生在读」）。{@code valid_start} / {@code valid_end}
     * 两列<b>保留但永远不写</b>（生产表已存在，删列要迁移而收益为零）。
     *
     * <p><b>本轮真正的风险不是删多了，是删漏了一处</b>：漏掉的那一处会成为全系统
     * <b>唯一</b>还在判有效期的地方，行为与其余各处不一致，<b>而且不报错</b>。
     * {@code GrantNoValidityIT} 用一条 {@code valid_end} 是过去时间的授权行
     * 把所有读路径钉住 —— 任一处判定回来，那条就红。
     *
     * <p><b>踩过的坑留在文档里</b>（04-实施计划.md 的 D7 与 F-92，已标注
     * 「已随取消有效期而消失」但正文原样保留）：曾经有两处写 {@code valid_end > NOW()}、
     * 一处写 {@code >=}，只在到期那一秒结论相反，两边都返回 200。
     * 两年后有人要做「试听一个月」会重新实现有效期，他需要知道上次是怎么翻车的。
     *
     * <p><b>若将来要把谓词加回来</b>：本常量会被拼进 {@code <script>} 形式的注解 SQL，
     * 那种写法要按 XML 解析，裸的 {@code <} 会让 MyBatis 在<b>启动时</b>抛
     * {@code SAXParseException}；换成 {@code &lt;} 又会让非 {@code <script>} 的那两条
     * 把转义符原样发给 MySQL。把不等号掉个个儿（{@code NOW() >= valid_start}）
     * 两种形式都不用特殊处理。
     */
    String NOT_DELETED = " AND deleted_at = 0 ";

    /**
     * 点查：该节点对该资源有没有授权行。<b>{@code target_node_id} 精确相等，不回溯祖先链。</b>
     *
     * <p><b>方法名里的 {@code Active} 现在只等于「未被撤销」</b>（授权无有效期，见
     * {@link #NOT_DELETED}）—— 名字没改，是因为它在四个模块里被引用，
     * 而语义收窄这件事写在常量那一处比散在四个调用点更不容易漏读。
     */
    @Select("SELECT COUNT(*) FROM org_resource_grant "
            + " WHERE resource_type = #{resourceType} AND resource_id = #{resourceId} "
            + "   AND target_node_id = #{nodeId} " + NOT_DELETED)
    int countActiveGrant(@Param("resourceType") int resourceType,
                         @Param("resourceId") Long resourceId,
                         @Param("nodeId") Long nodeId);

    /** 列表：该节点被授权的全部资源 ID（未撤销的）。 */
    @Select("SELECT DISTINCT resource_id FROM org_resource_grant "
            + " WHERE resource_type = #{resourceType} AND target_node_id = #{nodeId} " + NOT_DELETED)
    List<Long> selectActiveResourceIds(@Param("resourceType") int resourceType,
                                       @Param("nodeId") Long nodeId);

    /** 按资源统计当前被授权的目标节点数（03-03 §1.1 的 {@code grantedNodeCount}）。 */
    @Select("<script>"
            + "SELECT resource_id AS resourceId, COUNT(DISTINCT target_node_id) AS targetCount "
            + "  FROM org_resource_grant "
            + " WHERE resource_type = #{resourceType} "
            + "   AND resource_id IN "
            + "   <foreach collection='resourceIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach>"
            + NOT_DELETED
            + " GROUP BY resource_id"
            + "</script>")
    List<GrantCountRow> countActiveTargets(@Param("resourceType") int resourceType,
                                           @Param("resourceIds") List<Long> resourceIds);

    /**
     * 「这些节点里，谁当前持有这些资源」—— 契约 §2.5 规则 9 的链判定用它。
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
            + NOT_DELETED
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
