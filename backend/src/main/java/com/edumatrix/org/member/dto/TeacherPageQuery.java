package com.edumatrix.org.member.dto;

/** 接口 11 教师分页列表的查询参数（03-02 §5.1）。 */
public class TeacherPageQuery {

    private Integer pageNum;

    private Integer pageSize;

    /** 查询范围起点节点 ID，默认当前用户所在节点，按其<b>子树</b>过滤。 */
    private Long nodeId;

    /** 姓名，模糊匹配。 */
    private String realName;

    /** 教师工号，精确匹配。 */
    private String teacherNo;

    /** 任教科目，精确匹配。 */
    private String subject;

    /** 手机号，精确匹配。 */
    private String phone;

    /** 节点状态：0 正常 1 停用。 */
    private Integer status;

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
