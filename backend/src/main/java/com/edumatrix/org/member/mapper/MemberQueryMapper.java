package com.edumatrix.org.member.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * 四个列表接口的联表查询（03-02 接口 7 / 11 / 15 / 16）。
 *
 * <h2>子树过滤一律用 {@code ancestors} 前缀，<b>不用 {@code FIND_IN_SET}</b></h2>
 * <p>{@code FIND_IN_SET} 是列上的函数、<b>必然全表扫</b>，且
 * {@code check_backend_conventions.sh} 检查② 会 grep 出它。这里走
 * {@code idx_ancestors} 的前缀范围扫描，与 {@code OrgNodeMapper#rebuildSubtreeAncestors}
 * 及 {@code OrgStudentMapper#countActiveStudentsInSubtree} 同一写法：
 * <pre>
 * n.id = #{rootId}                             ← 起点自身
 * OR n.ancestors = #{prefix}                   ← 直接子节点
 * OR n.ancestors LIKE CONCAT(#{prefix}, ',%')  ← 更深的后代
 * </pre>
 * <p><b>两个前缀分支缺一不可</b>：直接子节点的 {@code ancestors} <b>恰好等于</b>前缀，
 * 没有后面那个逗号，只写 {@code LIKE} 会把它们整层漏掉。
 *
 * <p><b>也不用 {@code SubtreeScopeHelper#subtreeNodeIds} 拼 {@code IN}</b>：
 * 机构根管理员的子树是全机构（单租户上限约 1.1 万节点），把 id 全捞回 Java 再拼
 * {@code IN} 是白付一次大结果集的代价，而这里只要一页。子树<b>判定</b>（越界与否）
 * 仍走 {@code SubtreeScopeHelper}，两者分工不同。
 *
 * <p>租户条件由插件注入，这里一个字不写；{@code deleted_at = 0} 手写
 * （注解 SQL 不受 {@code @TableLogic} 管）。
 */
@Mapper
public interface MemberQueryMapper {

    /**
     * 接口 7 管理员分页列表。
     *
     * <p>§4.1 数据权限逐字：「只返回当前用户<b>子树</b>内的管理员节点（{@code node_type=1}），
     * <b>不含自己</b>」—— {@code n.id <> #{selfNodeId}} 就是「不含自己」那半句。
     * 漏掉它的话，管理员每次打开下级管理员列表都会看到自己排在第一行。
     *
     * <p>{@code directOnly=true} 只取直接下级（{@code parent_id = rootId}）。
     */
    @Select("<script>"
            + "SELECT n.id AS nodeId, n.node_name AS nodeName, n.parent_id AS parentNodeId, "
            + "       n.sort, n.status, n.remark, n.create_time AS createTime, "
            + "       n.update_time AS updateTime, "
            + "       u.id AS userId, u.username, u.real_name AS realName, u.phone, "
            + "       u.last_login_time AS lastLoginTime "
            + "  FROM org_node n "
            + "  JOIN sys_user u ON u.id = n.ref_user_id AND u.deleted_at = 0 "
            + " WHERE n.deleted_at = 0 AND n.node_type = 1 AND n.id &lt;&gt; #{selfNodeId} "
            + "<choose>"
            + "  <when test='directOnly'> AND n.parent_id = #{rootId} </when>"
            + "  <otherwise> AND (n.ancestors = #{prefix} "
            + "                   OR n.ancestors LIKE CONCAT(#{prefix}, ',%')) </otherwise>"
            + "</choose>"
            + "<if test='realName != null'> AND u.real_name LIKE CONCAT('%', #{realName}, '%') </if>"
            + "<if test='phone != null'> AND u.phone = #{phone} </if>"
            + "<if test='status != null'> AND n.status = #{status} </if>"
            + " ORDER BY n.sort, n.id"
            + "</script>")
    IPage<AdminRow> pageAdmins(IPage<AdminRow> page,
                               @Param("rootId") Long rootId,
                               @Param("prefix") String prefix,
                               @Param("selfNodeId") Long selfNodeId,
                               @Param("directOnly") boolean directOnly,
                               @Param("realName") String realName,
                               @Param("phone") String phone,
                               @Param("status") Integer status);

    /** 接口 11 教师分页列表（§5.1）。 */
    @Select("<script>"
            + "SELECT t.id, t.node_id AS nodeId, t.user_id AS userId, t.teacher_no AS teacherNo, "
            + "       t.subject, t.title, t.entry_date AS entryDate, "
            + "       t.student_count AS studentCount, t.remark, "
            + "       t.create_time AS createTime, t.update_time AS updateTime, "
            + "       n.parent_id AS parentNodeId, n.status, "
            + "       u.username, u.real_name AS realName, u.phone "
            + "  FROM org_teacher t "
            + "  JOIN org_node n ON n.id = t.node_id AND n.deleted_at = 0 "
            + "  JOIN sys_user u ON u.id = t.user_id AND u.deleted_at = 0 "
            + " WHERE t.deleted_at = 0 "
            + "   AND (n.id = #{rootId} OR n.ancestors = #{prefix} "
            + "        OR n.ancestors LIKE CONCAT(#{prefix}, ',%')) "
            + "<if test='realName != null'> AND u.real_name LIKE CONCAT('%', #{realName}, '%') </if>"
            + "<if test='teacherNo != null'> AND t.teacher_no = #{teacherNo} </if>"
            + "<if test='subject != null'> AND t.subject = #{subject} </if>"
            + "<if test='phone != null'> AND u.phone = #{phone} </if>"
            + "<if test='status != null'> AND n.status = #{status} </if>"
            + " ORDER BY n.sort, t.id"
            + "</script>")
    IPage<TeacherRow> pageTeachers(IPage<TeacherRow> page,
                                   @Param("rootId") Long rootId,
                                   @Param("prefix") String prefix,
                                   @Param("realName") String realName,
                                   @Param("teacherNo") String teacherNo,
                                   @Param("subject") String subject,
                                   @Param("phone") String phone,
                                   @Param("status") Integer status);

    /**
     * 接口 16 学生分页列表（§6.1）与接口 15 教师名下学员列表（§5.5）共用。
     *
     * <p>§5.5 逐字：「与接口 16（学生分页列表）传 {@code nodeId=教师节点ID} +
     * {@code directOnly=true} <b>等价</b>」——所以是同一条 SQL，不是两条。
     *
     * <p><b>本查询以 {@code org_student} 为驱动表</b>，这一点是刻意的：
     * 没有档案行的学生节点（03-01 §2.2 建出的孤儿数据，F-22）<b>查不出来</b>，
     * 因为它连主键 {@code id}（{@code org_student.id}）都没有。
     * 本节其余接口的 {@code {id}} 全部是这个主键（第 6 节导语），
     * 所以「列出它」在结构上就写不出来 —— <b>这不是选了一种口径，是另一种口径写不出来</b>。
     *
     * <p>{@code unassigned=true}：§6.1「捞取『已归属管理员但尚未分配导师』的学员
     * （其父节点 {@code node_type = 1}）」。
     */
    @Select("<script>"
            + "SELECT s.id, s.node_id AS nodeId, s.user_id AS userId, "
            + "       s.student_no AS studentNo, s.guardian_name AS guardianName, "
            + "       s.guardian_phone AS guardianPhone, s.status, "
            + "       s.quit_time AS quitTime, s.quit_reason AS quitReason, "
            + "       s.archive_time AS archiveTime, s.archive_reason AS archiveReason, "
            + "       s.anonymized_at AS anonymizedAt, s.remark, "
            + "       s.create_time AS createTime, s.update_time AS updateTime, "
            + "       n.parent_id AS parentNodeId, p.node_name AS parentNodeName, "
            + "       p.node_type AS parentNodeType, "
            + "       u.username, u.real_name AS realName, u.phone "
            + "  FROM org_student s "
            + "  JOIN org_node n ON n.id = s.node_id AND n.deleted_at = 0 "
            + "  JOIN sys_user u ON u.id = s.user_id AND u.deleted_at = 0 "
            + "  LEFT JOIN org_node p ON p.id = n.parent_id AND p.deleted_at = 0 "
            + " WHERE s.deleted_at = 0 "
            + "<choose>"
            + "  <when test='directOnly'> AND n.parent_id = #{rootId} </when>"
            + "  <otherwise> AND (n.id = #{rootId} OR n.ancestors = #{prefix} "
            + "                   OR n.ancestors LIKE CONCAT(#{prefix}, ',%')) </otherwise>"
            + "</choose>"
            + "<if test='unassigned'> AND p.node_type = 1 </if>"
            + "<if test='status != null'> AND s.status = #{status} </if>"
            + "<if test='realName != null'> AND u.real_name LIKE CONCAT('%', #{realName}, '%') </if>"
            + "<if test='studentNo != null'> AND s.student_no = #{studentNo} </if>"
            + "<if test='phone != null'> AND u.phone = #{phone} </if>"
            + "<if test='beginTime != null'> AND s.create_time &gt;= #{beginTime} </if>"
            + "<if test='endTime != null'> AND s.create_time &lt;= #{endTime} </if>"
            + " ORDER BY s.id"
            + "</script>")
    IPage<StudentRow> pageStudents(IPage<StudentRow> page,
                                   @Param("rootId") Long rootId,
                                   @Param("prefix") String prefix,
                                   @Param("directOnly") boolean directOnly,
                                   @Param("unassigned") boolean unassigned,
                                   @Param("status") Integer status,
                                   @Param("realName") String realName,
                                   @Param("studentNo") String studentNo,
                                   @Param("phone") String phone,
                                   @Param("beginTime") LocalDateTime beginTime,
                                   @Param("endTime") LocalDateTime endTime);

    /**
     * 接口 7 的三个子树计数（{@code subAdminCount} / {@code teacherCount} / {@code studentCount}）。
     *
     * <p>一次查全页，<b>不逐行查</b> —— 一页最多 100 行，逐行就是 300 次往返。
     *
     * <p>{@code studentCount} 按 {@code org_student.status = 0} 计（§4.1 字段说明逐字
     * 「其子树内<b>在读</b>学生数量（{@code org_student.status=0}）」），
     * 与 F-22 定案的三处口径一致 —— <b>不</b>读 {@code org_node.student_count} 那个冗余列，
     * 那是给移动事务维护的，这里要的是当下的真值。
     */
    @Select("<script>"
            + "SELECT r.id AS rootId, "
            + "  (SELECT COUNT(1) FROM org_node c WHERE c.deleted_at = 0 AND c.node_type = 1 "
            + "     AND (c.ancestors = r.self_prefix OR c.ancestors LIKE CONCAT(r.self_prefix, ',%'))"
            + "  ) AS subAdminCount, "
            + "  (SELECT COUNT(1) FROM org_node c WHERE c.deleted_at = 0 AND c.node_type = 2 "
            + "     AND (c.ancestors = r.self_prefix OR c.ancestors LIKE CONCAT(r.self_prefix, ',%'))"
            + "  ) AS teacherCount, "
            + "  (SELECT COUNT(1) FROM org_student s2 "
            + "     JOIN org_node c ON c.id = s2.node_id AND c.deleted_at = 0 "
            + "    WHERE s2.deleted_at = 0 AND s2.status = 0 "
            + "     AND (c.ancestors = r.self_prefix OR c.ancestors LIKE CONCAT(r.self_prefix, ',%'))"
            + "  ) AS studentCount "
            + "FROM (SELECT id, CONCAT(ancestors, ',', id) AS self_prefix FROM org_node "
            + "       WHERE deleted_at = 0 AND id IN "
            + "<foreach collection='nodeIds' item='nid' open='(' separator=',' close=')'>#{nid}</foreach>"
            + "     ) r"
            + "</script>")
    List<SubtreeStatRow> selectSubtreeStats(@Param("nodeIds") List<Long> nodeIds);

    /**
     * 接口 15 的 {@code assignTime}：取 {@code org_node_change_log} 中最近一条
     * {@code change_type = 2} 的 {@code change_time}。
     *
     * <p>§5.5 字段说明逐字：「取 {@code org_node_change_log} 中最近一条 {@code change_type=2}
     * 的 {@code change_time}；<b>建档即挂在该导师下时取建档时间</b>」——
     * 后半句就是 {@code change_type IN (1, 2)} 里的 {@code 1}：那种学员从来没被「分配」过，
     * 只写过一条建档轨迹。只取 2 会让这一列对「建档即分配」的学员恒为 {@code null}。
     */
    @Select("<script>"
            + "SELECT node_id AS nodeId, MAX(change_time) AS assignTime "
            + "  FROM org_node_change_log "
            + " WHERE deleted_at = 0 AND change_type IN (1, 2) AND node_id IN "
            + "<foreach collection='nodeIds' item='nid' open='(' separator=',' close=')'>#{nid}</foreach>"
            + " GROUP BY node_id"
            + "</script>")
    List<AssignTimeRow> selectAssignTimes(@Param("nodeIds") List<Long> nodeIds);

    /** 接口 7 的行。 */
    class AdminRow {
        private Long nodeId;
        private String nodeName;
        private Long parentNodeId;
        private Integer sort;
        private Integer status;
        private String remark;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
        private Long userId;
        private String username;
        private String realName;
        private String phone;
        private LocalDateTime lastLoginTime;

        public Long getNodeId() {
            return nodeId;
        }

        public void setNodeId(Long nodeId) {
            this.nodeId = nodeId;
        }

        public String getNodeName() {
            return nodeName;
        }

        public void setNodeName(String nodeName) {
            this.nodeName = nodeName;
        }

        public Long getParentNodeId() {
            return parentNodeId;
        }

        public void setParentNodeId(Long parentNodeId) {
            this.parentNodeId = parentNodeId;
        }

        public Integer getSort() {
            return sort;
        }

        public void setSort(Integer sort) {
            this.sort = sort;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public LocalDateTime getCreateTime() {
            return createTime;
        }

        public void setCreateTime(LocalDateTime createTime) {
            this.createTime = createTime;
        }

        public LocalDateTime getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(LocalDateTime updateTime) {
            this.updateTime = updateTime;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public LocalDateTime getLastLoginTime() {
            return lastLoginTime;
        }

        public void setLastLoginTime(LocalDateTime lastLoginTime) {
            this.lastLoginTime = lastLoginTime;
        }
    }

    /** 接口 11 的行。 */
    class TeacherRow {
        private Long id;
        private Long nodeId;
        private Long userId;
        private String teacherNo;
        private String subject;
        private String title;
        private LocalDate entryDate;
        private Integer studentCount;
        private String remark;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
        private Long parentNodeId;
        private Integer status;
        private String username;
        private String realName;
        private String phone;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getNodeId() {
            return nodeId;
        }

        public void setNodeId(Long nodeId) {
            this.nodeId = nodeId;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getTeacherNo() {
            return teacherNo;
        }

        public void setTeacherNo(String teacherNo) {
            this.teacherNo = teacherNo;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public LocalDate getEntryDate() {
            return entryDate;
        }

        public void setEntryDate(LocalDate entryDate) {
            this.entryDate = entryDate;
        }

        public Integer getStudentCount() {
            return studentCount;
        }

        public void setStudentCount(Integer studentCount) {
            this.studentCount = studentCount;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public LocalDateTime getCreateTime() {
            return createTime;
        }

        public void setCreateTime(LocalDateTime createTime) {
            this.createTime = createTime;
        }

        public LocalDateTime getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(LocalDateTime updateTime) {
            this.updateTime = updateTime;
        }

        public Long getParentNodeId() {
            return parentNodeId;
        }

        public void setParentNodeId(Long parentNodeId) {
            this.parentNodeId = parentNodeId;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }

    /** 接口 15 / 16 的行。 */
    class StudentRow {
        private Long id;
        private Long nodeId;
        private Long userId;
        private String studentNo;
        private String guardianName;
        private String guardianPhone;
        private Integer status;
        private LocalDateTime quitTime;
        private String quitReason;
        private LocalDateTime archiveTime;
        private Integer archiveReason;
        private LocalDateTime anonymizedAt;
        private String remark;
        private LocalDateTime createTime;
        private LocalDateTime updateTime;
        private Long parentNodeId;
        private String parentNodeName;
        private Integer parentNodeType;
        private String username;
        private String realName;
        private String phone;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getNodeId() {
            return nodeId;
        }

        public void setNodeId(Long nodeId) {
            this.nodeId = nodeId;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getStudentNo() {
            return studentNo;
        }

        public void setStudentNo(String studentNo) {
            this.studentNo = studentNo;
        }

        public String getGuardianName() {
            return guardianName;
        }

        public void setGuardianName(String guardianName) {
            this.guardianName = guardianName;
        }

        public String getGuardianPhone() {
            return guardianPhone;
        }

        public void setGuardianPhone(String guardianPhone) {
            this.guardianPhone = guardianPhone;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public LocalDateTime getQuitTime() {
            return quitTime;
        }

        public void setQuitTime(LocalDateTime quitTime) {
            this.quitTime = quitTime;
        }

        public String getQuitReason() {
            return quitReason;
        }

        public void setQuitReason(String quitReason) {
            this.quitReason = quitReason;
        }

        public LocalDateTime getArchiveTime() {
            return archiveTime;
        }

        public void setArchiveTime(LocalDateTime archiveTime) {
            this.archiveTime = archiveTime;
        }

        public Integer getArchiveReason() {
            return archiveReason;
        }

        public void setArchiveReason(Integer archiveReason) {
            this.archiveReason = archiveReason;
        }

        public LocalDateTime getAnonymizedAt() {
            return anonymizedAt;
        }

        public void setAnonymizedAt(LocalDateTime anonymizedAt) {
            this.anonymizedAt = anonymizedAt;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public LocalDateTime getCreateTime() {
            return createTime;
        }

        public void setCreateTime(LocalDateTime createTime) {
            this.createTime = createTime;
        }

        public LocalDateTime getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(LocalDateTime updateTime) {
            this.updateTime = updateTime;
        }

        public Long getParentNodeId() {
            return parentNodeId;
        }

        public void setParentNodeId(Long parentNodeId) {
            this.parentNodeId = parentNodeId;
        }

        public String getParentNodeName() {
            return parentNodeName;
        }

        public void setParentNodeName(String parentNodeName) {
            this.parentNodeName = parentNodeName;
        }

        public Integer getParentNodeType() {
            return parentNodeType;
        }

        public void setParentNodeType(Integer parentNodeType) {
            this.parentNodeType = parentNodeType;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }

    /** 接口 7 的三个子树计数。 */
    class SubtreeStatRow {
        private Long rootId;
        private Integer subAdminCount;
        private Integer teacherCount;
        private Integer studentCount;

        public Long getRootId() {
            return rootId;
        }

        public void setRootId(Long rootId) {
            this.rootId = rootId;
        }

        public Integer getSubAdminCount() {
            return subAdminCount;
        }

        public void setSubAdminCount(Integer subAdminCount) {
            this.subAdminCount = subAdminCount;
        }

        public Integer getTeacherCount() {
            return teacherCount;
        }

        public void setTeacherCount(Integer teacherCount) {
            this.teacherCount = teacherCount;
        }

        public Integer getStudentCount() {
            return studentCount;
        }

        public void setStudentCount(Integer studentCount) {
            this.studentCount = studentCount;
        }
    }

    /** 接口 15 的 {@code assignTime}。 */
    class AssignTimeRow {
        private Long nodeId;
        private LocalDateTime assignTime;

        public Long getNodeId() {
            return nodeId;
        }

        public void setNodeId(Long nodeId) {
            this.nodeId = nodeId;
        }

        public LocalDateTime getAssignTime() {
            return assignTime;
        }

        public void setAssignTime(LocalDateTime assignTime) {
            this.assignTime = assignTime;
        }
    }
}
