package com.edumatrix.system.tenant.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 模块 04 对 {@code org_node} / {@code org_student} 的<b>四条窄读写</b>。
 *
 * <h2>⚠ 临时构件：{@code org} 领域建成后本类应删除</h2>
 * <p>与 {@code system/user/mapper/StudentQuotaMapper}、{@code SystemOrgNodeMapper}
 * <b>同批交接</b>，已登记进 {@code system/user/entity/SystemOrgNode} 的交接清单
 * （那份清单必须完整——删完前几个就以为交接完毕，正是这类临时构件最典型的漏网方式）。
 * 模块 06/07 建成后：子树节点数与根节点改名 → {@code org/node} 的 Service；
 * 在读学生数 → {@code org/member} 的 Service。
 *
 * <h2>为什么四条都显式写 {@code tenant_id}，而不靠插件注入</h2>
 * <p>调用方全部是 §5 的六个接口，<b>它们只对 {@code super_admin} 开放</b>，
 * 而超管会话下租户插件走"整体放行"通道（{@code ignoreTable} 返回 true），
 * <b>一个条件都不注入</b>。不显式写的后果各不相同但都不报错：
 * 统计类会数出全平台的数、改名会改到别人的节点、级联删除会删掉别的租户的树。
 * 取值一律来自<b>被操作的那一行租户数据</b>，不是会话（超管的会话租户是 0），
 * 这是契约 §2.8 规则 1「从数据显式取」。
 *
 * <p>写的都是<b>等值条件</b>，不是 {@code OR tenant_id = 0}（检查①grep 的是后者）。
 */
@Mapper
public interface TenantOrgMapper {

    /**
     * 该机构子树内的节点总数（不含已删除），供 §5.2 的 {@code nodeCount}
     * 「供平台侧粗略了解规模」。
     *
     * <p><b>按 {@code tenant_id} 数，而不是按 {@code ancestors} 前缀 LIKE</b>：
     * 契约 §2.1 保证「机构以下所有节点继承同一 {@code tenant_id}」，
     * 于是"该机构的子树"与"该租户的全部节点"是同一个集合，等值条件走
     * {@code idx_tenant_parent_sort} 最左前缀，比前缀 LIKE 便宜且不会漏。
     */
    @Select("SELECT COUNT(1) FROM org_node WHERE tenant_id = #{tenantId} AND deleted_at = 0")
    long countLiveNodes(@Param("tenantId") Long tenantId);

    /**
     * 该租户当前<b>在读</b>（{@code org_student.status = 0}）学生数。
     *
     * <p>口径与 {@code StudentQuotaMapper#countActiveStudents} <b>必须一致</b>
     * （§E 的 F-22 定案：一律按 {@code org_student} 的在读行数计）——
     * 同一个上限两套算法的话，模块 07 上线后同一个租户会算出两个不同的学生数。
     * 用于 §5.1/§5.2 的 {@code currentStudentCount} 与 §5.4 的
     * 「{@code maxStudentCount} 不得低于当前在读数」。
     */
    @Select("SELECT COUNT(1) FROM org_student WHERE tenant_id = #{tenantId} "
            + "AND status = 0 AND deleted_at = 0")
    long countActiveStudents(@Param("tenantId") Long tenantId);

    /**
     * §5.4：机构名称同步更新机构根节点的 {@code node_name}（「随之影响全机构的
     * {@code nodePath} 展示」）。
     *
     * <p>两个条件都要：{@code id} 定位那一行，{@code tenant_id} 是超管会话下唯一的护栏。
     */
    @Update("UPDATE org_node SET node_name = #{nodeName} "
            + "WHERE id = #{nodeId} AND tenant_id = #{tenantId} AND deleted_at = 0")
    int updateNodeName(@Param("nodeId") Long nodeId,
                       @Param("tenantId") Long tenantId,
                       @Param("nodeName") String nodeName);

    /**
     * §5.5：机构根节点及其<b>整棵子树</b>一并逻辑删除。
     *
     * <p><b>这是全系统唯一允许级联逻辑删除整棵子树的场景</b>（§5.5 原文），
     * 与 02-组织机构分册「节点有子节点则拒绝删除（{@code 10108}）」的常规约束不同——
     * 租户删除是<b>租户边界级</b>操作。{@code sys_tenant.root_node_id} 指向保持不变，以便恢复。
     *
     * <p>同样按 {@code tenant_id} 圈定子树（理由见 {@link #countLiveNodes}）。
     *
     * <p><b>时间戳取数据库时钟</b>（{@code UNIX_TIMESTAMP(NOW(3)) * 1000}），与
     * {@code BaseEntity} 上 {@code @TableLogic} 的 {@code delval} <b>逐字相同</b>：
     * 一次租户删除会写两张表（{@code sys_tenant} 由 MyBatis-Plus 写、{@code org_node} 由本条写），
     * 从 Java 侧传时间戳就是把<b>应用服务器时钟与数据库时钟的差</b>写进同一次删除的两半
     * ——而 {@code AuditFieldHandler} 的类注释里已经立过这条规矩：让它们只认数据库这一个时钟。
     * 单条 UPDATE 内 {@code NOW(3)} 取值恒定，故整棵子树共用同一个毫秒值，
     * 将来要恢复时认得出「这批是一起被删的」。
     */
    @Update("UPDATE org_node SET deleted_at = UNIX_TIMESTAMP(NOW(3)) * 1000 "
            + "WHERE tenant_id = #{tenantId} AND deleted_at = 0")
    int softDeleteTenantSubtree(@Param("tenantId") Long tenantId);

    /**
     * 批量取多个租户的在读学生数（§5.1 分页列表的 {@code currentStudentCount}）。
     *
     * <p>一次查询而不是逐行查——本页最多 100 个租户，逐行就是 100 次往返。
     * 返回的行数<b>可能少于传入的 id 数</b>（一个学生都没有的租户不出现在结果里），
     * 这是正确行为，调用方按缺省 0 补齐。
     */
    @Select("""
            <script>
            SELECT tenant_id AS tenantId, COUNT(1) AS studentCount FROM org_student
             WHERE status = 0 AND deleted_at = 0
               AND tenant_id IN <foreach collection="tenantIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             GROUP BY tenant_id
            </script>
            """)
    List<TenantStudentCount> countActiveStudentsByTenants(@Param("tenantIds") List<Long> tenantIds);

    /** {@link #countActiveStudentsByTenants} 的行。 */
    class TenantStudentCount {
        private Long tenantId;
        private Long studentCount;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getStudentCount() {
            return studentCount;
        }

        public void setStudentCount(Long studentCount) {
            this.studentCount = studentCount;
        }
    }
}
