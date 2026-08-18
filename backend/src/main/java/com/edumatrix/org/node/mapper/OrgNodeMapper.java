package com.edumatrix.org.node.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.org.node.entity.OrgNode;

/**
 * {@code org_node} 的读写。<b>移动事务的 7 步 SQL 全在这里</b>
 * （02-数据库设计 §3.1.3，逐条对应，编号写在各方法的注释里）。
 *
 * <h2>两条全局约定，每个方法都适用</h2>
 * <ol>
 *   <li><b>租户条件由插件注入，这里一个字都不写</b>。§3.1.3 的模板里写着
 *       {@code WHERE tenant_id = #{tenantId}}，那是<b>裸 SQL 形态</b>的模板；
 *       本项目由 {@code PlatformRowTenantLineInnerInterceptor} 在 SQL 层统一注入，
 *       手写会与插件注入的条件重复，更糟的是会让人以为「这里写了所以别处不用写」
 *       （{@code common/entity/TenantEntity} 的类注释逐字禁止手写）。
 *       <b>§3.1.3 要求的租户隔离一条不少，只是由插件承担。</b>
 *   <li><b>{@code deleted_at = 0} 必须手写</b>。{@code @TableLogic} 只对
 *       {@code BaseMapper} 生成的语句生效，注解 SQL 不受它管。
 * </ol>
 *
 * <h2>本 Mapper 没有一处 {@code FIND_IN_SET}</h2>
 * <p>契约 §7.1：它出现在慢查询日志中即视为缺陷；{@code check_backend_conventions.sh}
 * 的检查②会 grep。子树一律走 {@code idx_ancestors} 的前缀 LIKE，
 * 且<b>两个分支缺一不可、LIKE 必须以逗号收边</b>（理由见 {@link #rebuildSubtreeAncestors}）。
 */
@Mapper
public interface OrgNodeMapper extends BaseMapper<OrgNode> {

    // =====================================================================
    // §3.1.3 步骤 1：按 id 升序加锁
    // =====================================================================

    /**
     * <b>步骤 1</b>：对 {@code ids} 里的行按 <b>id 升序</b>加行锁。
     *
     * <pre>
     * SELECT ... FROM org_node WHERE id IN (...) ORDER BY id FOR UPDATE;
     * </pre>
     *
     * <p><b>{@code ORDER BY id} 是防死锁的唯一手段</b>（§3.1.3 硬要求 2、00-通用约定 §7.5）：
     * 两个管理员同时互相移动对方子树内的节点时，乱序加锁必然互相等待。
     * 顺序<b>固定</b>比顺序"通常一致"强 —— 后者在并发下就是随机的。
     *
     * <p><b>返回行数可能少于传入的 id 数</b>：不存在、已逻辑删除、或跨租户被插件过滤掉的
     * 都不会回来。调用方据此判 {@code 10101}（§3.4 校验 1），三种成因<b>不区分</b>
     * ——不暴露存在性。
     *
     * <h2>⚠ 锁<b>哪些行</b>不由本方法决定，由 {@code NodeMoveService#lockIds} 决定</h2>
     * <p>本方法只保证「给什么就按 id 升序锁什么」。集合的构成与完整论证<b>全部在
     * {@code NodeMoveService#lockIds} 的注释里</b>，不要在这里推断。
     *
     * <p><b>它不是「被移动节点 + 目标父」两行</b>。02-数据库设计 §3.1.3 的模板步骤 1
     * 写的是那两行，但同一模板的步骤 6 还要写<b>旧父</b>与<b>两条祖先链</b> ——
     * 那些行不在被排序的集合里。<b>实测：只锁两行时 10 并发交叉移动 6/10 被判死锁</b>，
     * {@code SHOW ENGINE INNODB STATUS} 的环里正是 {@code student_count} 的祖先链 UPDATE
     * 与旧父的 {@code child_count} UPDATE。所以调用方传进来的是
     * <b>本事务全部点写入行</b>的并集。
     *
     * <p>（此处曾写着「为什么锁两行就够」的一段论证，<b>那段论证是错的，已被上面的实测推翻</b>。
     * 它错在只考虑了「两个事务基于各自读到的旧 {@code ancestors} 分别重算」这一个竞态，
     * 漏掉了步骤 6 的冗余计数写入。留这句话在这里，是因为<b>Mapper 方法签名上方是后来者
     * 最先读到的位置</b>——不点破的话，下一个人会照着那段旧论证去"简化" {@code lockIds}。）
     */
    @Select("<script>"
            + "SELECT id, parent_id AS parentId, ancestors, node_name AS nodeName, "
            + "       node_type AS nodeType, ref_user_id AS refUserId, sort, status, "
            + "       child_count AS childCount, student_count AS studentCount, tenant_id AS tenantId "
            + "  FROM org_node "
            + " WHERE deleted_at = 0 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY id "
            + "   FOR UPDATE"
            + "</script>")
    List<OrgNode> selectForUpdateOrderById(@Param("ids") List<Long> ids);

    // =====================================================================
    // §3.1.3 步骤 4：更新被移动节点自身
    // =====================================================================

    /**
     * <b>步骤 4</b>：更新被移动节点的 {@code parent_id} 与自身 {@code ancestors}。
     *
     * <p><b>{@code update_by} 显式写</b>：{@code AuditFieldHandler} 只填
     * {@code BaseMapper} 走的实体更新，注解 SQL 要自己带上 —— §3.1.3 的模板里也写着它。
     */
    @Update("UPDATE org_node "
            + "   SET parent_id = #{targetParentId}, "
            + "       ancestors = #{newSelfAncestors}, "
            + "       update_by = #{operatorId} "
            + " WHERE id = #{movingId} AND deleted_at = 0")
    int updateSelfOnMove(@Param("movingId") Long movingId,
                         @Param("targetParentId") Long targetParentId,
                         @Param("newSelfAncestors") String newSelfAncestors,
                         @Param("operatorId") Long operatorId);

    // =====================================================================
    // §3.1.3 步骤 5：一条 UPDATE 重算整棵子树（本模块的核心）
    // =====================================================================

    /**
     * <b>步骤 5【核心】</b>：把旧路径前缀整体替换为新前缀，一条 UPDATE 重算整棵子树。
     *
     * <pre>
     * UPDATE org_node
     *    SET ancestors = CONCAT(#{newP}, SUBSTRING(ancestors, CHAR_LENGTH(#{oldP}) + 1))
     *  WHERE (ancestors = #{oldP} OR ancestors LIKE CONCAT(#{oldP}, ',%'));
     * </pre>
     *
     * <p>因子树内每一行的 {@code ancestors} 一定以 {@code oldP} 开头，
     * {@code SUBSTRING} 截掉旧前缀后拼上新前缀即可，<b>与深度无关，一条搞定</b>。
     *
     * <h2>两个分支缺一不可</h2>
     * <p>{@code ancestors = #{oldP}} 匹配<b>直接子节点</b>（它们的 {@code ancestors}
     * 恰好等于 {@code oldP}，后面没有逗号）；{@code LIKE CONCAT(#{oldP}, ',%')} 匹配更深的后代。
     * 只写 LIKE 会<b>漏掉整层直接子节点</b>。
     *
     * <h2>LIKE 必须以逗号收边</h2>
     * <p>写成 {@code LIKE CONCAT(oldP, '%')}（少一个逗号）时，{@code oldP} 结尾若是
     * {@code ...,100}，会误命中 {@code ...,1001}。<b>「雪花 ID 等长所以不会误命中」这个理由是错的</b>
     * —— 平台根的 id 是 {@code 0}，长度并不齐（{@code common/subtree/OrgNodeSubtreeMapper}
     * 里记过同一条）。
     *
     * <h2>返回值就是 affectedNodeCount 的来源</h2>
     * <p>本条命中的是<b>后代</b>：被移动节点自身的 {@code ancestors} 在步骤 4 已改成
     * {@code newSelfAncestors}，且它自身的 {@code ancestors} 从来不等于 {@code oldP}
     * （{@code oldP} 以它自己的 id 结尾，而 {@code ancestors} 不含本节点）。
     * 所以 §3.4 响应里的 {@code affectedNodeCount = 本方法返回值 + 1}，
     * <b>那个 +1 就是步骤 4 更新的被移动节点自身</b>（响应字段说明：「含被移动节点自身」）。
     * <b>不是差一错误。</b>
     *
     * @param oldPrefix 旧前缀 {@code oldP} = 旧 ancestors 为空 ? movingId : 旧 ancestors + ',' + movingId
     * @param newPrefix 新前缀 {@code newP} = newSelfAncestors + ',' + movingId
     */
    @Update("UPDATE org_node "
            + "   SET ancestors = CONCAT(#{newPrefix}, SUBSTRING(ancestors, CHAR_LENGTH(#{oldPrefix}) + 1)), "
            + "       update_by = #{operatorId} "
            + " WHERE deleted_at = 0 "
            + "   AND (ancestors = #{oldPrefix} OR ancestors LIKE CONCAT(#{oldPrefix}, ',%'))")
    int rebuildSubtreeAncestors(@Param("oldPrefix") String oldPrefix,
                                @Param("newPrefix") String newPrefix,
                                @Param("operatorId") Long operatorId);

    // =====================================================================
    // §3.1.3 步骤 6：冗余计数
    // =====================================================================

    /**
     * <b>步骤 6</b>：父节点 {@code child_count} 增减（旧父 -1、新父 +1）。
     *
     * <p>{@code GREATEST(..., 0)} 兜住减到负数：那本身是别处漏维护的信号，
     * 但让计数变成 {@code -1} 只会让「{@code > 0} 时禁止删除本节点」这条保护<b>反向失效</b>。
     */
    @Update("UPDATE org_node SET child_count = GREATEST(child_count + #{delta}, 0) "
            + " WHERE id = #{nodeId} AND deleted_at = 0")
    int addChildCount(@Param("nodeId") Long nodeId, @Param("delta") int delta);

    /**
     * <b>步骤 6</b>：沿一条祖先链批量增减 {@code student_count}。
     *
     * <h2>⚠ 绝不可写成 {@code WHERE id = ? OR FIND_IN_SET(id, '常量串')}</h2>
     * <p>§3.1.3 在这一步给了完整推导：{@code FIND_IN_SET} 的第一个参数是<b>列</b>，
     * 无法走索引，MySQL 必然全表扫描；而 InnoDB 的 UPDATE 会对<b>扫描过程中经过的每一行</b>
     * 加行锁（RR 下还有间隙锁）。这条 UPDATE 跑在一个<b>已持有 {@code FOR UPDATE} 锁</b>
     * 的事务里，等于<b>每次分配导师都把整张 {@code org_node}（单租户约 1.1 万行）锁一遍</b>：
     * 并发移动直接串行化，且极易与步骤 1 规定的 id 升序加锁顺序<b>形成死锁</b>。
     *
     * <p>{@code ancestors} 本就是逗号串，<b>Java 侧 split 后用 {@code IN}</b> 即可精确锁定几行
     * （一条祖先链约 5 行），走主键。
     *
     * @param nodeIds 旧父/新父<b>及其祖先链</b>（Java 侧 split 得到），已含父节点自身
     * @param delta   被移动子树内的<b>在读</b>学生数增量（迁出为负、迁入为正）
     */
    @Update("<script>"
            + "UPDATE org_node SET student_count = GREATEST(student_count + #{delta}, 0) "
            + " WHERE deleted_at = 0 AND id IN "
            + "<foreach collection='nodeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int addStudentCount(@Param("nodeIds") List<Long> nodeIds, @Param("delta") int delta);

    // =====================================================================
    // 校验与查询
    // =====================================================================

    /**
     * 被移动子树内<b>最深一行</b>的深度（= {@code ancestors} 的段数）；
     * 无后代时返回 {@code null}。
     *
     * <p>供深度上限 50 级的校验用（契约 §2.3 约束 5）：
     * <pre>新深度上限 = 目标父深度 + 1 + (子树最深深度 - 被移动节点深度)</pre>
     *
     * <p><b>必须在锁内调用</b>：锁外取到的是旧值，并发下另一个事务可能刚往这棵子树里
     * 又插/移进了更深的一层，于是这次移动放过了一棵实际会超限的树，
     * 第 51 级在写入时 {@code Data too long} 让整个复合事务回滚 —— 而那正是
     * 契约 §2.3 约束 5 要求「必须在服务层校验」的理由。
     *
     * <p>深度用逗号数 + 1 算（{@code LENGTH - LENGTH(REPLACE(...))} 是 MySQL 里数字符的惯用法），
     * 不建函数索引：这条一次移动只跑一次，且已被前缀条件收敛到子树内。
     */
    @Select("SELECT MAX(LENGTH(ancestors) - LENGTH(REPLACE(ancestors, ',', '')) + 1) "
            + "  FROM org_node "
            + " WHERE deleted_at = 0 "
            + "   AND (ancestors = #{prefix} OR ancestors LIKE CONCAT(#{prefix}, ',%'))")
    Integer selectMaxSubtreeDepth(@Param("prefix") String prefix);

    /**
     * 同一父节点下是否已有同名节点（§3.4 校验 9、§3.3 的 {@code 10102}）。
     *
     * <p>{@code excludeId} 用于改名时排除自己；移动时传 {@code null}。
     */
    @Select("<script>"
            + "SELECT COUNT(1) FROM org_node "
            + " WHERE parent_id = #{parentId} AND node_name = #{nodeName} AND deleted_at = 0 "
            + "<if test='excludeId != null'> AND id &lt;&gt; #{excludeId}</if>"
            + "</script>")
    long countSameNameSibling(@Param("parentId") Long parentId,
                              @Param("nodeName") String nodeName,
                              @Param("excludeId") Long excludeId);

    /**
     * 取子树内全部节点 id（<b>不含</b>作为前缀起点的那个节点自身）。命中 {@code idx_ancestors}。
     *
     * <p>用于 §3.5 的 {@code affectedNodeCount}。两个分支与逗号收边的理由同
     * {@link #rebuildSubtreeAncestors}。
     */
    @Select("SELECT id FROM org_node "
            + " WHERE deleted_at = 0 "
            + "   AND (ancestors = #{prefix} OR ancestors LIKE CONCAT(#{prefix}, ',%'))")
    List<Long> selectSubtreeIds(@Param("prefix") String prefix);

    /** 子树内节点数（不含起点自身）。 */
    @Select("SELECT COUNT(1) FROM org_node "
            + " WHERE deleted_at = 0 "
            + "   AND (ancestors = #{prefix} OR ancestors LIKE CONCAT(#{prefix}, ',%'))")
    long countSubtreeNodes(@Param("prefix") String prefix);

    // =====================================================================
    // §3.1 组织树查询
    // =====================================================================

    /**
     * 路径①<b>逐层展开</b>（默认懒加载）：取某节点的直接子节点。
     * 命中 {@code idx_tenant_parent_sort}，单层毫秒级。
     *
     * <p>§3.1 说明段：「不传 {@code parentId} 时只返回调用者所在节点的直接子节点；
     * 前端展开某节点时再带 {@code parentId} 拉下一层」。
     *
     * @param nodeTypes       为空表示不过滤类型
     * @param includeDisabled {@code false} 时排除 {@code status = 1}
     */
    @Select("<script>"
            + "SELECT id, parent_id AS parentId, ancestors, node_name AS nodeName, "
            + "       node_type AS nodeType, ref_user_id AS refUserId, sort, status, "
            + "       child_count AS childCount, student_count AS studentCount, tenant_id AS tenantId "
            + "  FROM org_node WHERE parent_id = #{parentId} AND deleted_at = 0 "
            + "<if test='nodeTypes != null and nodeTypes.size() > 0'> AND node_type IN "
            + "<foreach collection='nodeTypes' item='t' open='(' separator=',' close=')'>#{t}</foreach></if>"
            + "<if test='!includeDisabled'> AND status = 0</if>"
            + " ORDER BY sort ASC, id ASC"
            + "</script>")
    List<OrgNode> selectChildren(@Param("parentId") Long parentId,
                                 @Param("nodeTypes") List<Integer> nodeTypes,
                                 @Param("includeDisabled") boolean includeDisabled);

    /**
     * 路径②<b>前缀 LIKE 取整棵子树</b>（{@code deep=true}）。命中 {@code idx_ancestors}。
     *
     * <h2>{@code limit} 不是分页，是护栏</h2>
     * <p>§3.1 说明段给了硬上限 2000 个节点、超出返回 {@code 400} 要求收窄条件。
     * 调用方传 {@code 2001} 并在拿到 2001 行时判 {@code 400} ——
     * <b>先 LIMIT 再判，而不是先全捞回来再数</b>：机构根管理员的子树是全机构
     * （单租户约 1.1 万节点，响应体 5~8 MB），把它整个读进 JVM 再拒绝，
     * 拒绝的代价就和不拒绝一样大了。
     *
     * <p>过滤条件全部下推到 SQL：类型筛选是 §3.1 的常规用法
     * （{@code nodeTypes=1,2} 只画到教师层用于选授权目标），
     * 在 Java 侧过滤等于把被排除的那 1 万个学生也读回来。
     *
     * @param maxAbsoluteDepth 绝对深度上限（= 树根深度 + {@code maxDepth}）；{@code null} 表示不限
     * @param keyword          节点名模糊匹配；{@code null} 表示不过滤
     */
    @Select("<script>"
            + "SELECT id, parent_id AS parentId, ancestors, node_name AS nodeName, "
            + "       node_type AS nodeType, ref_user_id AS refUserId, sort, status, "
            + "       child_count AS childCount, student_count AS studentCount, tenant_id AS tenantId "
            + "  FROM org_node "
            + " WHERE deleted_at = 0 "
            + "   AND (ancestors = #{prefix} OR ancestors LIKE CONCAT(#{prefix}, ',%')) "
            + "<if test='nodeTypes != null and nodeTypes.size() > 0'> AND node_type IN "
            + "<foreach collection='nodeTypes' item='t' open='(' separator=',' close=')'>#{t}</foreach></if>"
            + "<if test='!includeDisabled'> AND status = 0</if>"
            + "<if test='maxAbsoluteDepth != null'>"
            + " AND (LENGTH(ancestors) - LENGTH(REPLACE(ancestors, ',', '')) + 1) &lt;= #{maxAbsoluteDepth}</if>"
            + "<if test='keyword != null'> AND node_name LIKE CONCAT('%', #{keyword}, '%')</if>"
            + " ORDER BY sort ASC, id ASC LIMIT #{limit}"
            + "</script>")
    List<OrgNode> selectSubtree(@Param("prefix") String prefix,
                                @Param("nodeTypes") List<Integer> nodeTypes,
                                @Param("includeDisabled") boolean includeDisabled,
                                @Param("maxAbsoluteDepth") Integer maxAbsoluteDepth,
                                @Param("keyword") String keyword,
                                @Param("limit") int limit);

    /**
     * 按 id 批量取节点。用于 {@code keyword} 命中后把<b>命中节点的祖先链</b>补回来
     * （§3.1：「命中节点及其全部祖先链一并返回，未命中分支不返回」）。
     */
    @Select("<script>"
            + "SELECT id, parent_id AS parentId, ancestors, node_name AS nodeName, "
            + "       node_type AS nodeType, ref_user_id AS refUserId, sort, status, "
            + "       child_count AS childCount, student_count AS studentCount, tenant_id AS tenantId "
            + "  FROM org_node WHERE deleted_at = 0 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY sort ASC, id ASC"
            + "</script>")
    List<OrgNode> selectByIds(@Param("ids") List<Long> ids);

    /**
     * §3.2 的 {@code childStat}：<b>直接子节点</b>按类型的数量分布（不含孙节点）。
     * 命中 {@code idx_parent_type}。
     */
    @Select("SELECT node_type AS nodeType, COUNT(1) AS cnt FROM org_node "
            + " WHERE parent_id = #{parentId} AND deleted_at = 0 GROUP BY node_type")
    List<NodeTypeCountRow> selectChildStat(@Param("parentId") Long parentId);

    /** {@code node_type → 数量} 的窄投影。 */
    class NodeTypeCountRow {
        private Integer nodeType;
        private Long cnt;

        public Integer getNodeType() {
            return nodeType;
        }

        public void setNodeType(Integer nodeType) {
            this.nodeType = nodeType;
        }

        public Long getCnt() {
            return cnt;
        }

        public void setCnt(Long cnt) {
            this.cnt = cnt;
        }
    }
}
