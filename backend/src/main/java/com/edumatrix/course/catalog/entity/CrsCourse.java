package com.edumatrix.course.catalog.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code crs_course} 课程表（03-03 §1，02-数据库设计 §4.2.1）。
 *
 * <h2>归属只有 {@code owner_node_id} 一个真相源</h2>
 * <p>契约 §4「资源归属唯一化」逐字：{@code crs_course} / {@code qb_question} /
 * {@code vod_video} 一律以 {@code owner_node_id} 表示归属，
 * <b>不再保留独立的 {@code teacher_id} / {@code creator} 归属字段</b>；
 * 需要作者署名时用通用字段 {@code create_by}。03-03 §0.2.1 同义重申。
 * <b>所以本类没有 {@code teacherId}，将来也不要加。</b>
 *
 * <h2>{@code lesson_count} / {@code total_duration} 是冗余列</h2>
 * <p>口径（C 定案）：<b>全部未删除课时，不加 {@code status} 限定</b> ——
 * 按 DDL 与 02-数据库设计 §4.2.1 的字面（「课时总数（冗余计数，课时增删时同步维护）」，
 * 两处都没有 status 限定）。
 * 学生端 03-03 §6.1 的 {@code lessonCount} <b>另行现算可见数</b>
 * （{@code status=1} 且未删除），与同一响应里 {@code progressPercent} 的分母同源 ——
 * 那条分母在 §6.1 说明里是写死的。两个口径分属两处、各自不说谎。
 *
 * <p>维护入口<b>唯一</b>：{@code course/catalog/service/CourseCounterService}
 * （实现 {@code common/course/CourseCounterRefresher}）。别处不得直写这两列。
 */
@TableName("crs_course")
public class CrsCourse extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** {@code 0} 草稿。 */
    public static final int STATUS_DRAFT = 0;
    /** {@code 1} 已上架。 */
    public static final int STATUS_ON_SHELF = 1;
    /** {@code 2} 已下架。 */
    public static final int STATUS_OFF_SHELF = 2;

    private String courseName;

    /** 归属节点（创建时写入创建者所在节点），<b>请求体不接受</b>（03-03 §1.3）。 */
    private Long ownerNodeId;

    /** 封面文件 {@code sys_file.id}。{@code coverUrl} 由它现签，见 {@code CourseService}。 */
    private Long coverFileId;

    private String subject;

    private String description;

    /** {@code 0} 草稿 {@code 1} 已上架 {@code 2} 已下架。 */
    private Integer status;

    /** 冗余：课时总数。见类注释。 */
    private Integer lessonCount;

    /** 冗余：视频总时长（秒）。见类注释。 */
    private Integer totalDuration;

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public Long getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(Long ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

    public Long getCoverFileId() {
        return coverFileId;
    }

    public void setCoverFileId(Long coverFileId) {
        this.coverFileId = coverFileId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
}
