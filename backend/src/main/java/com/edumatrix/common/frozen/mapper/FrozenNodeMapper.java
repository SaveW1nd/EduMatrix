package com.edumatrix.common.frozen.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 冻结判定的<b>降级查库</b>路径（契约 §2.3：Redis 不可用时鉴权必须降级为查库，<b>不得跳过校验</b>）。
 *
 * <p>只有一条查询，且它与登录侧的两段校验是<b>同一条 SQL</b> —— 这是有意的：
 * 「本人节点停用 / 祖先链中有被停用的管理员」这条规则全系统只能有一处定义。
 * 登录走它、每请求鉴权降级时也走它，两条路径不可能判出不同结果。
 *
 * <p><b>为什么不复用 {@code OrgNodeSubtreeMapper}</b>：那个 Mapper 的三条查询是
 * 契约 §2.4「子树查询选路表」的三条执行路径，回答的是「我能看到哪些数据」；
 * 本查询回答的是「我能不能进来」，是 §2.3 的停用语义。两者的条件、索引、变更理由都不同，
 * 混在一起会让下一个人以为改一处就够。
 */
@Mapper
public interface FrozenNodeMapper {

    /**
     * 契约 §2.3 的两段校验，一条 SQL 表达；命中任意一行即拒（{@code 10017}）。
     *
     * <pre>
     * ① id = #{nodeId}                          自身节点被停用（教师/学生的「仅本人」由本段生效）
     * ② id IN (祖先) AND node_type = 1           祖先链中的管理员被停用（分支冻结由本段生效）
     * </pre>
     *
     * <p><b>②段的 {@code node_type = 1} 不可省</b>：省了就变成「停用一个教师，
     * 他名下学员全部登不进来」—— 契约 §2.3 把这件事称为业务事故，PRD F1-2 的验收标准
     * 明确要求教师停用不级联。
     *
     * <p><b>①段同样不可省</b>：只写②段时，教师与学生<b>自身</b>的 {@code status = 1}
     * 永远不命中（{@code node_type = 1} 把它们排除在外），于是「停用一个教师」完全不生效。
     *
     * <p>命中 {@code idx_tenant_type_status(tenant_id, node_type, status)} 与主键；
     * 祖先约 5 个 id，是主键 {@code IN} 点查，不是扫描。租户条件由插件注入，此处一字不写。
     *
     * @param nodeId      本人所在节点
     * @param ancestorIds 祖先节点 id（<b>已由 {@code NodePath.parseAncestorIds} 跳过首位哨兵 0</b>；
     *                    哨兵是 {@code node_type = 0}，本就永远不命中②段）
     * @return 命中的第一个被停用节点 id；未命中返回 {@code null}
     */
    @Select({"<script>",
            "SELECT id FROM org_node",
            " WHERE deleted_at = 0 AND status = 1",
            "   AND ( id = #{nodeId}",
            "     <if test='ancestorIds != null and ancestorIds.size() > 0'>",
            "       OR ( node_type = 1 AND id IN",
            "            <foreach item='a' collection='ancestorIds' open='(' separator=',' close=')'>#{a}</foreach>",
            "          )",
            "     </if>",
            "       )",
            " LIMIT 1",
            "</script>"})
    Long selectFirstDisabled(@Param("nodeId") Long nodeId,
                             @Param("ancestorIds") List<Long> ancestorIds);
}
