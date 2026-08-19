package com.edumatrix.course.catalog.dto;

/** 接口 20 修改图文资料（03-03 §4.4）。{@code attachmentFileIds} <b>全量覆盖</b>。 */
public class MaterialUpdateReq {

    @jakarta.validation.constraints.NotBlank(message = "不能为空")
    @jakarta.validation.constraints.Size(min = 1, max = 200, message = "长度须为 1~200 字符")
    private String title;

    /**
     * 富文本 HTML，最大 2MB（03-03 §4.3）。
     *
     * <p><b>落库前一律经 {@code common/xss/HtmlSanitizer} 白名单过滤</b>（PRD F2-2 规则 1）；
     * 内嵌图片写成 {@code <img src="edumxfile:{fileId}">} 占位，出参时现签（D-3）。
     */
    @jakarta.validation.constraints.NotBlank(message = "不能为空")
    @jakarta.validation.constraints.Size(max = 2 * 1024 * 1024, message = "最大 2MB")
    private String content;

    /** 附件文件 ID 数组（{@code sys_file.id}），<b>最多 10 个</b>（03-03 §4.3 / PRD F2-2 规则 2）。 */
    @jakarta.validation.constraints.Size(max = 10, message = "最多 10 个附件")
    private java.util.List<Long> attachmentFileIds;

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

    public java.util.List<Long> getAttachmentFileIds() {
        return attachmentFileIds;
    }

    public void setAttachmentFileIds(java.util.List<Long> attachmentFileIds) {
        this.attachmentFileIds = attachmentFileIds;
    }
}
