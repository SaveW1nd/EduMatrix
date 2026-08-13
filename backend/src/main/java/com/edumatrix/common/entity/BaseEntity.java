package com.edumatrix.common.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;

/**
 * 通用字段基类（契约 §2.2）。<b>不含 {@code tenant_id}</b> —— 带租户的表继承
 * {@link TenantEntity}。
 *
 * <pre>
 * id           BIGINT       主键（雪花，非自增，由 common/id/IdWorker 生成）
 * create_by    BIGINT       创建人 user_id
 * create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
 * update_by    BIGINT       更新人 user_id
 * update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
 * deleted_at   BIGINT       NOT NULL DEFAULT 0  逻辑删除：0=未删除，删除时写毫秒时间戳
 * remark       VARCHAR(500) 备注
 * </pre>
 *
 * <h2>逻辑删除为什么是毫秒时间戳而不是 0/1</h2>
 * <p>0/1 方案下<b>同一业务键最多只能容纳一条已删除行</b> —— 「打标 → 去标 → 再打标 →
 * 再去标」到第二次去标就撞唯一键，而这类反复增删在 {@code org_student_tag}、
 * {@code sys_user_role}、{@code hw_answer_detail} 上都是常规操作。时间戳方案下每次删除
 * 取值不同，可容纳任意多条，且白得一个删除时间用于审计。唯一索引末尾统一追加
 * {@code deleted_at}，DDL 里已逐表就位。
 *
 * <p>所以 {@link TableLogic} 的 {@code delval} 是一个 <b>SQL 表达式</b>而不是常量，
 * 这也是它<b>不能写成 MyBatis-Plus 全局配置</b>（{@code logic-delete-value}）的原因 ——
 * 全局配置那一栏放不下函数调用。注解写在这里，全库实体继承，杜绝逐表各写一份。
 *
 * <h2>有 6 张表不继承本类（也不继承 TenantEntity），逐张说明</h2>
 * <p>它们不是 DDL 写漏了。契约 §2.2 末尾明写：「日志/心跳明细表可例外（允许归档清理；
 * 可不带 {@code update_by} / {@code remark}，登录与操作日志另可省略
 * {@code create_by} / {@code create_time}）」。
 *
 * <table border="1">
 *   <caption>不满足全部通用字段的 6 张表（对基线 DDL 逐列实测）</caption>
 *   <tr><th>表</th><th>缺什么</th><th>怎么办</th></tr>
 *   <tr><td>{@code sys_tenant}</td><td>{@code tenant_id}</td>
 *       <td>继承 <b>本类</b>（它是平台级表，不进租户插件）</td></tr>
 *   <tr><td>{@code sys_menu}</td><td>{@code tenant_id}</td>
 *       <td>继承 <b>本类</b>（同上）</td></tr>
 *   <tr><td>{@code sys_login_log}</td>
 *       <td>{@code create_by} / {@code create_time} / {@code update_by} / {@code remark}</td>
 *       <td><b>不继承</b>，字段自行声明（业务时间由 {@code login_time} 承担）</td></tr>
 *   <tr><td>{@code sys_oper_log}</td>
 *       <td>{@code create_by} / {@code create_time} / {@code update_by} / {@code remark}</td>
 *       <td><b>不继承</b>（业务时间由 {@code oper_time} 承担）</td></tr>
 *   <tr><td>{@code vod_play_auth_log}</td>
 *       <td>{@code create_by} / {@code update_by} / {@code remark}</td>
 *       <td><b>不继承</b></td></tr>
 *   <tr><td>{@code vod_heartbeat_log}</td>
 *       <td>{@code create_by} / {@code create_time} / {@code update_by} / {@code remark} /
 *           <b>{@code deleted_at}</b></td>
 *       <td><b>不继承</b> —— 见下方硬约束</td></tr>
 * </table>
 *
 * <p><b>{@code vod_heartbeat_log} 这一条是硬性的，不是风格问题</b>：它<b>根本没有
 * {@code deleted_at} 列</b>。一旦继承了带 {@link TableLogic} 的基类，MyBatis-Plus 会给
 * 每条查询注入 {@code AND deleted_at = 0}，直接 {@code Unknown column 'deleted_at'} 报错。
 * 而这张表是按月 RANGE 分区的亿级高频写入表，它的清理方式本来就是<b>归档删分区</b>，
 * 不是逻辑删除。
 *
 * <p><b>剩下 35 张业务表一律继承 {@link TenantEntity}。</b>
 */
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键。雪花 ID，非自增（契约 §2.2 / §1）。
     *
     * <p>{@link IdType#ASSIGN_ID} 会走 {@code MybatisPlusConfig} 注册的
     * {@code IdentifierGenerator}，后者委派给 {@code common/id/IdWorker} ——
     * 因此「实体不写 id 让 MP 填」与「业务代码显式 {@code IdWorker.nextId()}」
     * 用的是<b>同一个序列</b>，不会出现两套 workerId。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 创建人 {@code user_id}。由 {@link AuditFieldHandler} 在插入时自动填充。
     *
     * <p><b>一律指向 {@code sys_user.id}，不指向 {@code org_teacher.id}</b>（契约 §2.2）——
     * 管理员与教师都可能是创建者，而管理员没有 {@code org_teacher} 档案行。
     * <b>不设 {@code creator_id} 这类专用创建人列</b>：署名一律用本字段，
     * 归属一律用 {@code owner_node_id}，避免归属有两个真相源。
     *
     * <p><b>无会话入口下保持 {@code null}，不填 0</b> —— 理由见 {@link AuditFieldHandler}。
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    /**
     * 创建时间。<b>不做 Java 侧填充</b>：DDL 已是 {@code DEFAULT CURRENT_TIMESTAMP}，
     * 由数据库这一个时钟赋值（理由见 {@link AuditFieldHandler}）。
     */
    private LocalDateTime createTime;

    /** 更新人 {@code user_id}。由 {@link AuditFieldHandler} 在插入与更新时自动填充。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    /**
     * 更新时间。<b>不做 Java 侧填充</b>：DDL 已是
     * {@code ON UPDATE CURRENT_TIMESTAMP}。
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0 = 未删除；删除时写入当前毫秒时间戳。
     *
     * <p>核心业务数据（课程/题目/作业/答卷）<b>禁止物理删除</b>（契约 §2.2）。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3))*1000")
    private Long deletedAt;

    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(Long updateBy) {
        this.updateBy = updateBy;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
