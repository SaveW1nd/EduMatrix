package com.edumatrix.org.member.dto;

/**
 * 接口 16 学生分页列表的查询参数（03-02 §6.1）。
 *
 * <p><b>{@code tagIds} / {@code tagMatchMode} 不在本 DTO 里</b>：标签是模块 17 的交付物
 * （04-实施计划.md §A 接口分配把 30~36 分给模块 17），本模块没有 {@code org_student_tag}。
 * 不给字段比给了再静默忽略更难写错 —— 前端传了会收到 400 而不是一个「筛选没生效」的空列表。
 */
public class StudentPageQuery {

    private Integer pageNum;

    private Integer pageSize;

    /** 查询范围起点节点 ID，默认当前用户所在节点。 */
    private Long nodeId;

    /** {@code true} 只返回 {@code nodeId} 的直接学生子节点；默认 {@code false}。 */
    private Boolean directOnly;

    /** {@code true} 仅返回尚未分配导师的学员（父节点为管理员节点）。 */
    private Boolean unassigned;

    /** 学籍状态：0 在读 1 已退课 2 毕业归档；<b>不传查全部</b>。 */
    private Integer status;

    /** 学生姓名，模糊匹配。 */
    private String realName;

    /** 学号，精确匹配。 */
    private String studentNo;

    /** 手机号，精确匹配。 */
    private String phone;

    /** 建档时间起（含边界），{@code yyyy-MM-dd HH:mm:ss}。 */
    private String beginTime;

    /** 建档时间止（含边界），{@code yyyy-MM-dd HH:mm:ss}。 */
    private String endTime;

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

    public Boolean getDirectOnly() {
        return directOnly;
    }

    public void setDirectOnly(Boolean directOnly) {
        this.directOnly = directOnly;
    }

    public Boolean getUnassigned() {
        return unassigned;
    }

    public void setUnassigned(Boolean unassigned) {
        this.unassigned = unassigned;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getStudentNo() {
        return studentNo;
    }

    public void setStudentNo(String studentNo) {
        this.studentNo = studentNo;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getBeginTime() {
        return beginTime;
    }

    public void setBeginTime(String beginTime) {
        this.beginTime = beginTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }
}
