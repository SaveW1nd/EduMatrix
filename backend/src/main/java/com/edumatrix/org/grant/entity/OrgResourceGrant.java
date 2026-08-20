package com.edumatrix.org.grant.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code org_resource_grant} —— 资源逐级下发授权表（契约 §2.5，全系统最高频鉴权表）。
 *
 * <h2>{@code deleted_at} 写毫秒时间戳，不写 1</h2>
 * <p>由 {@code BaseEntity} 上的 {@code @TableLogic(delval = "UNIX_TIMESTAMP(NOW(3))*1000")}
 * 保证。DDL 的列注释与 02-数据库设计 §3.3.3 的示例 SQL 都写着「撤销授权 = 置 1」——
 * <b>那是错的</b>，已登记（D1）：唯一索引
 * {@code uk_resource_target(resource_type, resource_id, target_node_id, deleted_at)}
 * <b>含 deleted_at</b>，若每次撤销都写同一个 1，「授→撤→再授→再撤」到第二次撤销就撞唯一键。
 * 时间戳方案下每次取值不同，可容纳任意多条已删除行（契约 §2.2 逐字）。
 *
 * <h2>重新授权只能 INSERT 新行，不能「UPSERT 复活」</h2>
 * <p>02-数据库设计 §3.3.3 要点 3 建议
 * {@code INSERT ... ON DUPLICATE KEY UPDATE deleted_at = 0} 复活原行 ——
 * <b>在实际的 UK 下不可能生效</b>：插入时 {@code deleted_at = 0}，冲突目标是
 * {@code (type, rid, target, 0)}，<b>只会命中未删行，永远碰不到已删行</b>。
 * 故：历史撤销行原样保留（审计链完整），全部查询一律带 {@code deleted_at = 0}。
 * 同样已登记（D3），本轮只登记不改文档。
 */
@TableName("org_resource_grant")
public class OrgResourceGrant extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 手动选择（默认）。 */
    public static final int SOURCE_MANUAL = 1;
    /** 按节点批量。 */
    public static final int SOURCE_NODE = 2;
    /** 按标签批量。 */
    public static final int SOURCE_TAG = 3;
    /** 按名下全体。 */
    public static final int SOURCE_ALL_STUDENTS = 4;
    /** 按权限模板。 */
    public static final int SOURCE_TEMPLATE = 5;

    /** 受管资源类型：1 课程 2 题目 3 视频（契约 §5 {@code resource_type}）。 */
    private Integer resourceType;

    private Long resourceId;

    /** 被授权的目标节点（管理员 / 教师 / 学生任一类型，逐级显式授权、无继承）。 */
    private Long targetNodeId;

    /** {@code null} = 立即生效。 */
    private LocalDateTime validStart;

    /** {@code null} = 永久有效。 */
    private LocalDateTime validEnd;

    /** 授权来源，<b>仅为审计标记，不是授权依据</b>（03-02 §9.2 逐字）。 */
    private Integer grantSource;

    /** 来源对象 ID（节点 / 标签 / 模板）；{@code grantSource = 1} 时为 {@code null}。 */
    private Long sourceRefId;

    /** 授权操作人 {@code sys_user.id}。 */
    private Long grantBy;

    private LocalDateTime grantTime;

    /** 授权来源的中文名（契约 §5 {@code grant_source}）。 */
    public static String sourceName(Integer grantSource) {
        if (grantSource == null) {
            return null;
        }
        return switch (grantSource) {
            case SOURCE_MANUAL -> "手动选择";
            case SOURCE_NODE -> "按节点批量";
            case SOURCE_TAG -> "按标签批量";
            case SOURCE_ALL_STUDENTS -> "按名下全体";
            case SOURCE_TEMPLATE -> "按权限模板";
            default -> null;
        };
    }

    public Integer getResourceType() {
        return resourceType;
    }

    public void setResourceType(Integer resourceType) {
        this.resourceType = resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public Long getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(Long targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    public LocalDateTime getValidStart() {
        return validStart;
    }

    public void setValidStart(LocalDateTime validStart) {
        this.validStart = validStart;
    }

    public LocalDateTime getValidEnd() {
        return validEnd;
    }

    public void setValidEnd(LocalDateTime validEnd) {
        this.validEnd = validEnd;
    }

    public Integer getGrantSource() {
        return grantSource;
    }

    public void setGrantSource(Integer grantSource) {
        this.grantSource = grantSource;
    }

    public Long getSourceRefId() {
        return sourceRefId;
    }

    public void setSourceRefId(Long sourceRefId) {
        this.sourceRefId = sourceRefId;
    }

    public Long getGrantBy() {
        return grantBy;
    }

    public void setGrantBy(Long grantBy) {
        this.grantBy = grantBy;
    }

    public LocalDateTime getGrantTime() {
        return grantTime;
    }

    public void setGrantTime(LocalDateTime grantTime) {
        this.grantTime = grantTime;
    }
}
