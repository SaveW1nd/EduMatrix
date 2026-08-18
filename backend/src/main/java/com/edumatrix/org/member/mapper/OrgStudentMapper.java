package com.edumatrix.org.member.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.org.member.entity.OrgStudent;

/**
 * {@code org_student} 的读写。
 *
 * <h2>本接口的前两条方法是从模块 06 的 {@code NodeMemberMapper} 迁来的</h2>
 * <p>那是模块 06 为了在 {@code org/node} 里读写 {@code org_student} 而开的<b>窄构件</b>，
 * 登记在 {@code com.edumatrix.org.node} 的 {@code package-info}（模块 06 自己的那张清单），
 * 交接对象逐字写着「<b>交给模块 07 的 {@code org/member}</b>」。本模块建成真实体后已完成交接，
 * {@code NodeMemberMapper} 随之删除。<b>SQL 与注释逐字保留</b> —— 它们记的是口径，不是实现细节。
 *
 * <p>租户条件由插件注入，这里一个字不写；注解 SQL 不受 {@code @TableLogic} 管，
 * 故 {@code deleted_at = 0} 手写。
 */
@Mapper
public interface OrgStudentMapper extends BaseMapper<OrgStudent> {

    /**
     * 学生节点的学籍状态（{@code 0 在读 / 1 已退课 / 2 毕业归档}）；无档案行时返回 {@code null}。
     *
     * <p>§3.4 校验 10「被移动学生节点学籍状态为 0 在读」的判据，违反 → {@code 10203}。
     *
     * <p><b>返回 {@code null} 与「不是在读」不是一回事</b>，调用方要分开处理：
     * 03-01 §2.2 允许超管经 {@code /system/users} 建出<b>没有 {@code org_student} 档案</b>
     * 的学生节点（F-22 未定案，模块 03 已按「保留现状」落地）。那种节点<b>不是</b>
     * 「已退课/已归档」，用 {@code 10203} 拒绝它是错的 —— 判据见本方法调用处的注释。
     *
     * <p><b>模块 07 落地后已核实：本模块的 20 个接口一个都碰不到那种节点</b> ——
     * 接口 15 / 16 / 18 / 19 / 20 / 21 / 22 / 23 / 25 / 26 一律以<b>学生档案 ID</b>
     * （{@code org_student.id}）寻址（03-02 第 6 节导语），没有档案行就没有 id。
     * 唯一能碰到它的入口是<b>接口 4 移动节点</b>（全系统唯一以 {@code org_node.id} 寻址的写接口），
     * 也就是本方法当初的调用点。<b>两处口径不冲突，也不在同一条路径上</b>
     * （04-实施计划.md §E 的 F-22 新证据段）。
     */
    @Select("SELECT status FROM org_student WHERE node_id = #{nodeId} AND deleted_at = 0")
    Integer selectStudentStatus(@Param("nodeId") Long nodeId);

    /**
     * 被移动子树内的<b>在读</b>学生数（{@code org_student.status = 0}），<b>含被移动节点自身</b>。
     *
     * <p>这是 02-数据库设计 §3.1.3 步骤 6 里 {@code movedStudentCnt} 的来源。
     *
     * <h2>口径必须与另外两处一致</h2>
     * <p>{@code StudentQuotaMapper#countActiveStudents} 与
     * {@code TenantOrgMapper#countActiveStudents} 都按 {@code org_student.status = 0} 计
     * （F-22 已定案：<b>不</b>按 {@code org_node} 里 {@code node_type = 3} 的节点数）。
     * {@code org_node.student_count} 的 DDL 注释逐字是「子树内<b>在读</b>学生节点总数」，
     * 与本口径自洽 —— 三处同口径，否则同一个租户会算出两个学生数。
     *
     * <h2>为什么是 JOIN 而不是先取子树 id 再 IN</h2>
     * <p>被移动子树可能有上万行，把 id 全捞回 Java 再拼 {@code IN} 是白付一次大结果集的代价；
     * 而这条只要一个数。{@code org_node} 侧走 {@code idx_ancestors} 前缀范围扫描，
     * {@code org_student} 侧走 {@code uk_node_id}。
     *
     * <p>两个前缀分支与逗号收边的理由同 {@code OrgNodeMapper#rebuildSubtreeAncestors}；
     * {@code n.id = #{movingNodeId}} 那一支是<b>被移动节点自身</b> ——
     * 它自己若是在读学生（分配导师/转交管理员的常规形态），也要计入。
     */
    @Select("SELECT COUNT(1) FROM org_student s "
            + " JOIN org_node n ON n.id = s.node_id AND n.deleted_at = 0 "
            + " WHERE s.status = 0 AND s.deleted_at = 0 "
            + "   AND (n.id = #{movingNodeId} "
            + "        OR n.ancestors = #{prefix} OR n.ancestors LIKE CONCAT(#{prefix}, ',%'))")
    long countActiveStudentsInSubtree(@Param("movingNodeId") Long movingNodeId,
                                      @Param("prefix") String prefix);

    // =====================================================================
    // 以下为模块 07 新增
    // =====================================================================

    /** 学号机构内唯一（{@code 10202}）。租户条件由插件注入，故「机构内」天然成立。 */
    @Select("SELECT COUNT(1) FROM org_student "
            + " WHERE student_no = #{studentNo} AND deleted_at = 0 "
            + "   AND (#{excludeId} IS NULL OR id <> #{excludeId})")
    long countByStudentNo(@Param("studentNo") String studentNo,
                          @Param("excludeId") Long excludeId);

    /** 按节点 id 取档案；无档案行返回 {@code null}（那正是 F-22 的孤儿学生）。 */
    @Select("SELECT * FROM org_student WHERE node_id = #{nodeId} AND deleted_at = 0")
    OrgStudent selectByNodeId(@Param("nodeId") Long nodeId);

    /**
     * 本租户<b>在读</b>学生总数，{@code 10207} 的判据。
     *
     * <p>与 {@code StudentQuotaMapper#countActiveStudents} 同口径同 SQL —— F-22 定案要求的
     * 「三处同口径」就是指这里。
     */
    @Select("SELECT COUNT(1) FROM org_student WHERE status = 0 AND deleted_at = 0")
    long countActiveStudents();

    /**
     * 子树内全部<b>在读</b>学员的档案 id（接口 24 的 {@code nodeId} 模式）。
     *
     * <p>§6.9 原文：「{@code nodeId}：按<b>子树</b>整批归档，归档该节点整棵子树内全部
     * {@code status=0} 在读学员（节点本身不变、不删除）」，并注明该模式
     * 「<b>自动只取在读学员，因此不会触发 {@code 10208}</b>」——
     * {@code status = 0} 这个条件就写在这条 SQL 里，不是调用方过滤的。
     */
    @Select("SELECT s.id FROM org_student s "
            + " JOIN org_node n ON n.id = s.node_id AND n.deleted_at = 0 "
            + " WHERE s.status = 0 AND s.deleted_at = 0 "
            + "   AND (n.id = #{nodeId} "
            + "        OR n.ancestors = #{prefix} OR n.ancestors LIKE CONCAT(#{prefix}, ',%'))")
    List<Long> selectActiveStudentIdsInSubtree(@Param("nodeId") Long nodeId,
                                               @Param("prefix") String prefix);

    /**
     * 本租户的在读学生上限（{@code sys_tenant.max_student_count}），{@code 10207} 的另一半。
     *
     * <h2>读 {@code sys_tenant} 是工单明确授权的，不是越界</h2>
     * <p>04-实施计划.md 模块 07 的「涉及表」<b>只读</b>栏逐字：{@code sys_tenant}（学生上限）。
     * 检查③ 拦的是 Java 包之间的 {@code import}，不是 SQL 里的表名 ——
     * 与 {@code NodeAccountMapper} 读 {@code sys_user} / {@code sys_role} 是同一形状。
     *
     * <h2>口径与 {@code system/user/mapper/StudentQuotaMapper} 必须一致</h2>
     * <p>那一侧（03-01 §2.2 建号路径）已按 F-22 定案实现：上限按 {@code org_student}
     * 的<b>在读行数</b>计。两处同口径是 F-22 定案的原话 ——
     * 「同一个上限两套算法，同一个租户会算出两个不同的学生数，<b>这种分叉查起来极难</b>」。
     *
     * <p><b>{@code sys_tenant} 不带 {@code tenant_id} 列</b>（它本身就是租户表），
     * 租户插件压根不进它，所以这里按主键点查。
     *
     * @return {@code null} 或 {@code <= 0} 表示不限
     */
    @Select("SELECT max_student_count FROM sys_tenant WHERE id = #{tenantId} AND deleted_at = 0")
    Integer selectMaxStudentCount(@Param("tenantId") Long tenantId);

    /**
     * 归档恢复：{@code status} → 0，并<b>清空</b>退课与归档的四列（§6.10 事务内第一步）。
     *
     * <h2>{@code archive_reason} / {@code archive_time} 必须一起清</h2>
     * <p>不清的话，一名<b>已经复课在读</b>的学员会在原归档日满 30 日时被脱敏任务扫到
     * （扫描条件只看 {@code archive_reason=2}、{@code archive_time} 与 {@code anonymized_at}，
     * <b>不看 {@code status}</b>）—— 而脱敏不可逆。
     *
     * <p><b>{@code anonymized_at} 不在清空之列</b>：已脱敏的学员根本走不到这里
     * （{@code 10209} 在前面拦下），而它是「这个人提过删除请求」的<b>唯一证据</b>，
     * 任何路径都不得抹掉（契约 §2.2 同源原则）。
     *
     * <p>用注解 SQL 而不是实体 {@code updateById}：MyBatis-Plus 的实体更新<b>跳过 null 字段</b>，
     * 而这里要的<b>恰恰是把它们写成 null</b>。
     */
    @Update("UPDATE org_student SET status = 0, quit_time = NULL, quit_reason = NULL, "
            + "       archive_time = NULL, archive_reason = NULL, update_by = #{operatorId} "
            + " WHERE id = #{studentId} AND deleted_at = 0")
    int clearLifecycleFields(@Param("studentId") Long studentId,
                             @Param("operatorId") Long operatorId);

    /**
     * 全部启用中的租户 id，<b>脱敏任务逐租户扫描的驱动列表</b>。
     *
     * <h2>为什么必须逐租户扫，而不是一次跨租户扫完</h2>
     * <p>脱敏任务没有会话，租户插件取不到租户。有两条路：
     * <ol>
     *   <li><b>{@code TenantHelper.ignore()} 跨租户扫一遍</b> —— <b>已否决</b>。
     *       {@code ignore()} 是逃生舱，每新增一处都要能说清「为什么这个查询非跨租户不可」
     *       （{@code check_backend_conventions.sh} 检查④ 会把它列进清单）。
     *       本任务并不需要跨租户<b>查询</b>，它只是需要<b>依次进入每个租户</b>；
     *   <li><b>先取租户清单，再逐个 {@code runWithTenant} 包住扫描与写入</b>（本方案）。
     *       契约 §2.8 规则 1「从数据显式取」要的正是这个形状。
     * </ol>
     *
     * <p><b>本方法自身不需要 {@code ignore()}</b>：{@code sys_tenant} 是全库仅有的两张
     * <b>不带 {@code tenant_id} 列</b>的表之一（{@code EduMatrixTenantLineHandler}
     * 的 {@code TABLES_WITHOUT_TENANT_COLUMN}，穷举实测），<b>压根不进插件</b>。
     *
     * <p>读 {@code sys_tenant} 是工单授权的：模块 07「涉及表」<b>只读</b>栏列着它。
     */
    @Select("SELECT id FROM sys_tenant WHERE status = 0 AND deleted_at = 0 ORDER BY id")
    List<Long> selectActiveTenantIds();

    /**
     * 脱敏：覆写监护人姓名与手机号为掩码，回填 {@code anonymized_at}。
     *
     * <p><b>三件事一条 UPDATE</b>：只覆写不回填，下次调度会把掩码值再掩码一遍；
     * 只回填不覆写，则原值留在库里而系统认为已经删了 —— 后者是一次<b>合规事故</b>。
     * 一条语句让这两种中间态都不存在。
     *
     * <p><b>绝不用置 NULL</b>（契约 §2.2 同源原则表第 2 行）：那会把「本来就没填」
     * 和「提过删除请求已脱敏」混成同一状态，<b>而后者恰是监管问询时唯一要证明的事</b>。
     */
    @Update("UPDATE org_student SET guardian_name = #{guardianName}, "
            + "       guardian_phone = #{guardianPhone}, anonymized_at = #{anonymizedAt} "
            + " WHERE id = #{studentId} AND anonymized_at IS NULL AND deleted_at = 0")
    int anonymize(@Param("studentId") Long studentId,
                  @Param("guardianName") String guardianName,
                  @Param("guardianPhone") String guardianPhone,
                  @Param("anonymizedAt") java.time.LocalDateTime anonymizedAt);

    /**
     * 脱敏任务的扫描条件：<b>三个与门，一个都不能少</b>（03-02 §6.9、PRD F7-3、模块 07 规则 10）。
     *
     * <pre>
     * archive_reason = 2            因监护人删除请求归档  ← 少了它会误脱敏毕业校友
     * archive_time  &lt;= NOW() - 30d  30 日撤回窗口已过     ← 少了它会脱掉还能撤回的
     * anonymized_at IS NULL         尚未脱敏             ← 少了它会重复脱敏
     * </pre>
     *
     * <h2>第一个条件是最容易漏、且漏了不会有任何东西报错的那个</h2>
     * <p>模块 07 规则 11 逐字：「{@code archiveReason = 1}（正常毕业）满 30 日<b>不脱敏</b>——
     * 毕业校友的联系方式必须保留」。只测「reason=2 满 30 日会脱敏」是测不出这个缺陷的，
     * 必须有一条 {@code reason=1} 满 30 日<b>不被脱敏</b>的对照用例
     * （{@code StudentAnonymizeIT#graduatedStudentIsNotAnonymized}）。
     * <b>而脱敏不可逆</b> —— 误脱之后没有任何补救手段。
     *
     * <p><b>本方法跨租户扫描</b>：由 Job 在 {@code TenantHelper.ignore()} 之外调用，
     * 逐租户用 {@code runWithTenant} 包住（契约 §2.8 规则 1「从数据显式取」）——
     * 具体见 {@code AnonymizeArchivedStudentJob}。这里只声明条件，不管租户。
     */
    @Select("SELECT * FROM org_student "
            + " WHERE archive_reason = 2 "
            + "   AND archive_time <= #{deadline} "
            + "   AND anonymized_at IS NULL "
            + "   AND deleted_at = 0 "
            + " ORDER BY id LIMIT #{limit}")
    List<OrgStudent> selectAnonymizeCandidates(
            @Param("deadline") java.time.LocalDateTime deadline,
            @Param("limit") int limit);
}
