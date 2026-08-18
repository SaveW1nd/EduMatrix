package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;

/** 接口 16 学生分页列表的行（03-02 §6.1）。 */
public class StudentVO {

    /** 学生档案 ID（{@code org_student.id}），<b>本节其余接口的 {@code {id}}</b>。 */
    private Long id;

    /** 学生节点 ID（{@code org_node.id}），资源授权目标使用。 */
    private Long nodeId;

    private Long userId;

    private String username;

    private String studentNo;

    private String realName;

    private String phone;

    private String guardianName;

    private String guardianPhone;

    private Long parentNodeId;

    private String parentNodeName;

    /** 当前父节点类型：2 表示已分配导师；1 表示尚未分配导师。 */
    private Integer parentNodeType;

    /** 导师节点 ID；<b>父节点非教师节点时为 {@code null}</b>。 */
    private Long teacherNodeId;

    private String teacherName;

    private String nodePath;

    /** 学籍状态（契约 §5 {@code student_status}）：0 在读 1 已退课 2 毕业归档。 */
    private Integer status;

    private LocalDateTime quitTime;

    private String quitReason;

    private LocalDateTime archiveTime;

    /** 归档原因：1 正常毕业 2 因监护人删除请求。<b>决定脱不脱敏。</b> */
    private Integer archiveReason;

    /** 脱敏完成时间；非空即不可归档恢复（{@code 10209}）。 */
    private LocalDateTime anonymizedAt;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getStudentNo() {
        return studentNo;
    }

    public void setStudentNo(String studentNo) {
        this.studentNo = studentNo;
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

    public Long getTeacherNodeId() {
        return teacherNodeId;
    }

    public void setTeacherNodeId(Long teacherNodeId) {
        this.teacherNodeId = teacherNodeId;
    }

    public String getTeacherName() {
        return teacherName;
    }

    public void setTeacherName(String teacherName) {
        this.teacherName = teacherName;
    }

    public String getNodePath() {
        return nodePath;
    }

    public void setNodePath(String nodePath) {
        this.nodePath = nodePath;
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
}
