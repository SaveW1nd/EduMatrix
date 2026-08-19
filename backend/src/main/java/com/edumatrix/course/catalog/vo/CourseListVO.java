package com.edumatrix.course.catalog.vo;

import java.time.LocalDateTime;

/**
 * 接口 1 课程分页列表的行（03-03 §1.1）。
 *
 * <p><b>{@code coverUrl} 是 {@code FileService#inlineSignedUrl(coverFileId)} 现签的
 * ≤30 分钟地址</b>，不是 {@code sys_file.file_url} 的原值 —— 那一列<b>只存对象键</b>，
 * 读出来直接返回等于下发了一条永久直链（{@code 00-通用约定} §7.4 第 1 行、D-2 定案，
 * 强制检查点见 {@code 04-实施计划.md} §B 模块 08「做完什么算做完」）。
 * 本地存储模式（开发/测试）下恒为 {@code null}，生产一律 OSS。
 *
 * <p>{@code grantedNodeCount} <b>仅 {@code grantType=1}（自有）的行返回真实值</b>，
 * 被授权行返回 {@code null} —— §1.1 响应字段说明逐字：
 * 「下级不得窥探同级/上级的授权面」。
 */
public class CourseListVO {

    private Long id;

    private String courseName;

    private Long coverFileId;

    /** 见类注释：现签的 ≤30 分钟签名地址，本地存储下为 null。 */
    private String coverUrl;

    private String subject;

    private Long ownerNodeId;

    private String ownerNodeName;

    /** 1 自有（{@code owner_node_id} = 我的节点）2 被授权。 */
    private Integer grantType;

    /** 0 草稿 1 已上架 2 已下架。 */
    private Integer status;

    /** 冗余列 {@code crs_course.lesson_count}：<b>全部未删除课时</b>（C 定案）。 */
    private Integer lessonCount;

    /** 冗余列 {@code crs_course.total_duration}，秒。 */
    private Integer totalDuration;

    /** 见类注释：被授权行恒为 null。 */
    private Integer grantedNodeCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public Long getCoverFileId() {
        return coverFileId;
    }

    public void setCoverFileId(Long coverFileId) {
        this.coverFileId = coverFileId;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Long getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(Long ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

    public String getOwnerNodeName() {
        return ownerNodeName;
    }

    public void setOwnerNodeName(String ownerNodeName) {
        this.ownerNodeName = ownerNodeName;
    }

    public Integer getGrantType() {
        return grantType;
    }

    public void setGrantType(Integer grantType) {
        this.grantType = grantType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getLessonCount() {
        return lessonCount;
    }

    public void setLessonCount(Integer lessonCount) {
        this.lessonCount = lessonCount;
    }

    public Integer getTotalDuration() {
        return totalDuration;
    }

    public void setTotalDuration(Integer totalDuration) {
        this.totalDuration = totalDuration;
    }

    public Integer getGrantedNodeCount() {
        return grantedNodeCount;
    }

    public void setGrantedNodeCount(Integer grantedNodeCount) {
        this.grantedNodeCount = grantedNodeCount;
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
