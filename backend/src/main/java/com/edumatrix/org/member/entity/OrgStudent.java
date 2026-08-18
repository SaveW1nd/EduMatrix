package com.edumatrix.org.member.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code org_student} 学生档案（1:1 {@code org_node} 学生节点 / 1:1 {@code sys_user}）。
 *
 * <p><b>归属由树表达，不在本表</b>：学生挂在哪个节点下就归谁管（03-02 第 6 节导语）。
 * 本表<b>没有</b> {@code teacher_id} / {@code parent_node_id} 这类列 ——
 * 想知道导师是谁，查 {@code org_node.parent_id} 那一行的 {@code node_type}。
 *
 * <h2>本表是「在读」这个口径的唯一权威</h2>
 * <p>{@code status = 0} 的行数就是在读学生数。三处按此计：
 * {@code StudentQuotaMapper#countActiveStudents}（模块 03 的 {@code max_student_count} 判定）、
 * {@code TenantOrgMapper#countActiveStudents}（模块 04 的租户详情）、
 * 本模块的 {@code OrgStudentMapper#countActiveStudentsInSubtree}（移动事务的
 * {@code student_count} 维护）。<b>F-22 已定案：不按 {@code org_node} 里
 * {@code node_type = 3} 的节点数计</b> —— 同一个上限两套算法，同一个租户会算出两个学生数。
 *
 * <h2>{@code archive_reason} 与 {@code anonymized_at}：两条路后果相反</h2>
 * <p>它们不是「归档」的附属信息，而是<b>决定要不要脱敏</b>的判据（03-02 §6.9、PRD F7-3）：
 * <ul>
 *   <li>{@code archive_reason = 2}（因监护人删除请求）→ 30 日撤回窗口 → 定时任务<b>脱敏</b>；
 *   <li>{@code archive_reason = 1}（正常毕业）→ 满 30 日<b>不脱敏</b>，
 *       毕业校友的联系方式必须保留（机构仍需联系他们）。
 * </ul>
 * <p>脱敏任务的扫描条件是<b>三个与门</b>：{@code archive_reason = 2 AND
 * archive_time <= NOW() - 30 天 AND anonymized_at IS NULL}。<b>少写第一个条件就会误脱敏毕业校友</b>，
 * 而那是不可逆的 —— 见 {@code OrgStudentMapper#selectAnonymizeCandidates}。
 */
@TableName("org_student")
public class OrgStudent extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 在读。契约 §5 {@code student_status}。 */
    public static final int STATUS_ACTIVE = 0;
    /** 已退课（流失口径的唯一数据来源，PRD F1-7 规则 6）。 */
    public static final int STATUS_QUIT = 1;
    /** 毕业归档。 */
    public static final int STATUS_ARCHIVED = 2;

    /** 正常毕业归档：仅改学籍状态，联系方式原样保留。 */
    public static final int ARCHIVE_REASON_GRADUATED = 1;
    /** 因监护人删除请求归档：启动 30 日脱敏倒计时（契约 §7.2 第 3 条）。 */
    public static final int ARCHIVE_REASON_DELETION_REQUEST = 2;

    /** 脱敏倒计时天数（03-02 §6.9、PRD F7-3）。 */
    public static final int ANONYMIZE_DELAY_DAYS = 30;

    /** 学生节点 ID（{@code org_node.id}，{@code node_type = 3}，叶子）。 */
    private Long nodeId;

    /** 账号 ID（{@code sys_user.id}）。 */
    private Long userId;

    /** 学号，机构内唯一（{@code 10202}）。 */
    private String studentNo;

    /**
     * 监护人姓名。
     *
     * <p><b>脱敏时覆写为姓氏 + {@code *}，绝不置 NULL</b>（契约 §2.2 同源原则表第 2 行）：
     * 置 NULL 会把「本来就没填」和「提过删除请求已脱敏」混成同一状态，
     * <b>而后者恰是监管问询时唯一要证明的事</b>。
     */
    private String guardianName;

    /** 监护人手机号。脱敏时覆写为掩码 {@code 138****5678}，理由同 {@link #guardianName}。 */
    private String guardianPhone;

    /** 学籍状态：0 在读 1 已退课 2 毕业归档。 */
    private Integer status;

    /** 退课时间（{@code status = 1} 时写入）。 */
    private LocalDateTime quitTime;

    /** 退课原因（流失分析依据）。 */
    private String quitReason;

    /** 归档时间（{@code status = 2} 时写入）。脱敏倒计时的起点。 */
    private LocalDateTime archiveTime;

    /** 归档原因：1 正常毕业 2 因监护人删除请求。<b>决定脱不脱敏。</b> */
    private Integer archiveReason;

    /**
     * 脱敏完成时间；{@code null} = 未脱敏。
     *
     * <p><b>非空即不可归档恢复（{@code 10209}）</b>：脱敏不可逆，原值不存于任何地方，
     * 恢复出来的是一个联系不上的账号，且违背了当初对监护人「已删除」的承诺（03-02 §6.10）。
     */
    private LocalDateTime anonymizedAt;

    /** 在读。 */
    public boolean isActive() {
        return status != null && status == STATUS_ACTIVE;
    }

    /** 已脱敏（{@code 10209} 的判据）。 */
    public boolean isAnonymized() {
        return anonymizedAt != null;
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
}
