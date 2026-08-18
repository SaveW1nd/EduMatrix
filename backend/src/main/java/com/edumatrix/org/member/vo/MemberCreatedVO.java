package com.edumatrix.org.member.vo;

/**
 * 三个建人接口（8 / 12 / 17）的响应（03-02 §4.2 / §5.2 / §6.2）。
 *
 * <p>{@code initPassword} <b>只在本次响应返回一次</b>，不落库、不可再查（PRD §7.3）。
 */
public class MemberCreatedVO {

    /** 档案 ID：教师为 {@code org_teacher.id}、学生为 {@code org_student.id}；<b>管理员无档案表，恒为 {@code null}</b>。 */
    private Long id;

    private Long nodeId;

    private Long userId;

    private String username;

    /** 初始密码<b>明文</b>，仅本次返回一次；库里只有 BCrypt 密文。 */
    private String initPassword;

    /** 恒为 1：首次登录强制改密。 */
    private Integer pwdResetFlag;

    private Long parentNodeId;

    private String ancestors;

    private String nodePath;

    /** 学生的学籍状态（恒 0 在读）；管理员与教师为 {@code null}。 */
    private Integer status;

    /** 教师的名下学员数（恒 0）；其余为 {@code null}。 */
    private Integer studentCount;

    /** 恒为 1 建档（{@code org_node_change_log.change_type}）。 */
    private Integer changeType;

    /**
     * 套用权限模板的结果。<b>本模块恒为 {@code null}</b> ——
     * 套用动作是模块 11/17 的交付物（04-实施计划.md §A 接口分配），
     * 传了 {@code templateId} 会在服务端留一条 WARN，不静默。
     */
    private Object templateApply;

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

    public String getInitPassword() {
        return initPassword;
    }

    public void setInitPassword(String initPassword) {
        this.initPassword = initPassword;
    }

    public Integer getPwdResetFlag() {
        return pwdResetFlag;
    }

    public void setPwdResetFlag(Integer pwdResetFlag) {
        this.pwdResetFlag = pwdResetFlag;
    }

    public Long getParentNodeId() {
        return parentNodeId;
    }

    public void setParentNodeId(Long parentNodeId) {
        this.parentNodeId = parentNodeId;
    }

    public String getAncestors() {
        return ancestors;
    }

    public void setAncestors(String ancestors) {
        this.ancestors = ancestors;
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

    public Integer getStudentCount() {
        return studentCount;
    }

    public void setStudentCount(Integer studentCount) {
        this.studentCount = studentCount;
    }

    public Integer getChangeType() {
        return changeType;
    }

    public void setChangeType(Integer changeType) {
        this.changeType = changeType;
    }

    public Object getTemplateApply() {
        return templateApply;
    }

    public void setTemplateApply(Object templateApply) {
        this.templateApply = templateApply;
    }
}
