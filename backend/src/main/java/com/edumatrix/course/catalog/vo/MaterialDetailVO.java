package com.edumatrix.course.catalog.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 接口 18 图文资料详情（03-03 §4.2）。
 *
 * <p><b>{@code content} 里的内嵌图片已由 {@code MaterialContentRewriter} 把
 * {@code edumxfile:{fileId}} 占位重写为 ≤30 分钟签名地址</b>（D-3 定案）。
 * 库里存的是占位符 —— 存 URL 等于正文里躺着一条永久直链，而它会随富文本被复制传播。
 */
public class MaterialDetailVO {

    private Long id;

    private String title;

    /** 见类注释：出参时占位符已重写为签名地址。 */
    private String content;

    private List<AttachmentVO> attachments;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public List<AttachmentVO> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<AttachmentVO> attachments) {
        this.attachments = attachments;
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
}
