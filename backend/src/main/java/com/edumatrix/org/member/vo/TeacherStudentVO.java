package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;

/** 接口 15 教师名下学员列表的行（03-02 §5.5）。 */
public class TeacherStudentVO {

    private Long id;

    private Long nodeId;

    private Long userId;

    private String studentNo;

    private String realName;

    private String phone;

    private String guardianName;

    private String guardianPhone;

    private Integer status;

    /**
     * 分配给该导师的时间：{@code org_node_change_log} 中最近一条 {@code change_type=2}
     * 的 {@code change_time}；<b>建档即挂在该导师下时取建档时间</b>（{@code change_type=1}）。
     */
    private LocalDateTime assignTime;

    /**
     * 最近一次学习心跳时间（{@code vod_watch_progress.last_heartbeat_time} 最大值）。
     *
     * <p><b>本模块恒为 {@code null}</b>：{@code vod_watch_progress} 是模块 13 的表，
     * 不在模块 07 的「涉及表」内。与接口 4 的 {@code resourceName} 是同一种处境 ——
     * 字段先按分册给出，取值等对应模块建表后补。
     */
    private LocalDateTime lastStudyTime;

    private LocalDateTime createTime;

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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getAssignTime() {
        return assignTime;
    }

    public void setAssignTime(LocalDateTime assignTime) {
        this.assignTime = assignTime;
    }

    public LocalDateTime getLastStudyTime() {
        return lastStudyTime;
    }

    public void setLastStudyTime(LocalDateTime lastStudyTime) {
        this.lastStudyTime = lastStudyTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
