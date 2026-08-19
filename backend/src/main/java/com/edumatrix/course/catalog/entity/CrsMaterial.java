package com.edumatrix.course.catalog.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code crs_material} 图文资料内容表（03-03 §4，02-数据库设计 §4.2.4）。
 *
 * <h2>{@code owner_node_id} 是管理端可见性的过滤列（D 定案，F-45）</h2>
 * <p><b>这是一处明知的分册推翻，不是漏读</b>：03-03 §4.1 / §4.2 / §4.4 / §4.5
 * 四处权限栏写的是「数据权限按契约 §2.4 子树规则过滤 <b>{@code create_by} 所在节点</b>」，
 * 而 DDL 与 02-数据库设计 §4.2.4 的列注释写的是「<b>管理端可见性按本列（{@code owner_node_id}）
 * 做子树过滤，此后不随创建人调岗漂移</b>」。
 *
 * <p>按权威顺序（契约 &gt; 03 六分册 &gt; 01-PRD &gt; 02-数据库设计 + DDL）本该取分册；
 * 契约 §4 的「资源归属唯一化」逐字只点名 {@code crs_course} / {@code qb_question} /
 * {@code vod_video} <b>三张</b>，§2.5 的「受管资源」也是这三张 ——
 * <b>都不覆盖 {@code crs_material}</b>，所以契约在这件事上是沉默的，构不成上位依据。
 * 需方裁决取 {@code owner_node_id}，理由是 {@code create_by} 会随创建人调岗
 * 让资料静默换归属，而 {@code owner_node_id} 是 NOT NULL 列、取分册口径则它永远没人读。
 * 已登记 <b>F-45</b>，分册待订正（本轮已改四处权限栏）。
 *
 * <h2>{@code content} 里存的是 {@code fileId} 占位，不是 URL（D-3）</h2>
 * <p>内嵌图片一律写成 {@code <img src="edumxfile:{fileId}">}，
 * 出参时由 {@code MaterialContentRewriter} 现签为 ≤30 分钟地址。
 * 存 URL 等于正文里躺着一条永久直链，而它会随富文本被复制传播。
 *
 * <p><b>{@code crs_material} 不是受管资源</b>（契约 §2.5 只列三张），
 * 不进 {@code org_resource_grant}；学生端可见性走「所属课时 → 课程 → 课程授权」。
 */
@TableName("crs_material")
public class CrsMaterial extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 归属节点（创建时写入创建者所在节点）。见类注释。 */
    private Long ownerNodeId;

    private String title;

    /** 富文本 HTML。<b>已经过 XSS 白名单过滤</b>（写入时），内嵌图片为 fileId 占位。 */
    private String content;

    /** JSON 数组字符串，元素为 {@code sys_file.id}。最多 10 个（03-03 §4.3）。 */
    private String attachmentFileIds;

    public Long getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(Long ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAttachmentFileIds() {
        return attachmentFileIds;
    }

    public void setAttachmentFileIds(String attachmentFileIds) {
        this.attachmentFileIds = attachmentFileIds;
    }
}
