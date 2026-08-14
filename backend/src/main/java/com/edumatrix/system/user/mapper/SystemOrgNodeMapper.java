package com.edumatrix.system.user.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.user.entity.SystemOrgNode;

/**
 * {@code org_node} 的窄读写，<b>只服务 03-01 §2.1 面包屑、§2.2 建号与 §2.4 删号</b>。
 *
 * <h2>⚠ 临时构件，与 {@link SystemOrgNode} 同批交接给模块 06</h2>
 * <p>处置与交接方式逐条见 {@link SystemOrgNode} 的类注释。
 *
 * <h2>这里没有一处 {@code FIND_IN_SET}</h2>
 * <p>契约 §7.1 写死：它出现在慢查询日志中即视为缺陷
 * （{@code scripts/check_backend_conventions.sh} 的检查②会 grep）。
 * 子树相关的判定一律走 {@code common/subtree/SubtreeScopeHelper}，本 Mapper 不重复实现。
 */
@Mapper
public interface SystemOrgNodeMapper extends BaseMapper<SystemOrgNode> {

    /**
     * 节点名称的批量点查，用于拼 {@code nodePath} 面包屑（§2.1 / §2.2 响应字段）。
     *
     * <p><b>返回行数比传入的 id 数少 1 是正确行为</b>：{@code ancestors} 的首位
     * {@code 0} 是平台根哨兵，契约 §2.9 定案不放行它，于是它被租户插件过滤掉。
     * 调用方用 {@code NodePath.parseAncestorIds} 拆祖先链时那个 0 已经被跳过，
     * 所以正常路径下不会踩到 —— 记在这里是为了让人不要"修"成放行哨兵。
     */
    @Select("<script>"
            + "SELECT id, node_name AS nodeName FROM org_node WHERE deleted_at = 0 AND id IN "
            + "<foreach collection='nodeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<NodeNameRow> selectNodeNames(@Param("nodeIds") List<Long> nodeIds);

    /**
     * 未删除的直接子节点数（§2.4 的 {@code 10108} 判据）。
     *
     * <p><b>实查而不是读 {@code org_node.child_count}</b>：本模块只在自己的建/删路径上维护
     * 那个冗余列，而节点还会被模块 06/07 的移动、建人、删人改动。
     * 拿一个「本模块只维护了一部分」的冗余列当删除保护的依据，
     * 等于把一次数据不一致直接兑换成误删。计数走 {@code idx_tenant_parent_sort} 的
     * {@code parent_id} 前缀，代价是一次索引点查。
     */
    @Select("SELECT COUNT(1) FROM org_node WHERE parent_id = #{nodeId} AND deleted_at = 0")
    long countLiveChildren(@Param("nodeId") Long nodeId);

    /**
     * 父节点 {@code child_count} 的增减（DDL：「增删/移动子节点时同步维护」）。
     *
     * <p>{@code GREATEST(..., 0)} 兜住减到负数：那本身是别处漏维护的信号，
     * 但让计数变成 {@code -1} 只会让「{@code > 0} 时禁止删除」这条保护<b>反向失效</b>。
     */
    @Update("UPDATE org_node SET child_count = GREATEST(child_count + #{delta}, 0) "
            + "WHERE id = #{nodeId} AND deleted_at = 0")
    int addChildCount(@Param("nodeId") Long nodeId, @Param("delta") int delta);

    /** {@code id → node_name} 的窄投影。 */
    class NodeNameRow {
        private Long id;
        private String nodeName;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getNodeName() {
            return nodeName;
        }

        public void setNodeName(String nodeName) {
            this.nodeName = nodeName;
        }
    }
}
