package com.edumatrix.course.catalog.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.edumatrix.common.file.InlineFileUrlProvider;
import com.edumatrix.common.xss.HtmlSanitizer;

/**
 * D-3 定案的出参重写：把正文里的 {@code <img src="edumxfile:{fileId}">} 占位
 * 换成 {@code inlineSignedUrl} 现签的 ≤30 分钟地址。
 *
 * <h2>为什么库里存占位而不是 URL</h2>
 * <p>{@code 04-实施计划.md} §B 模块 08 的强制检查点逐字：
 * 「不做重写就等于正文里躺着一条<b>永久直链</b>，而它会随富文本被复制传播」。
 * 签名地址只活 30 分钟，占位则永远指向「当前有权的人才看得到的那份文件」。
 *
 * <h2>为什么敢用正则</h2>
 * <p>库里的 {@code content} <b>一律是 {@link HtmlSanitizer} 的输出</b>（写入时过滤），
 * 而 OWASP 的渲染器总是把属性写成 {@code name="escapedValue"}（双引号、值做 HTML 转义），
 * 且 {@code <img src>} 的取值被白名单限死成 {@code edumxfile:\d+} 一种形状。
 * 因此这里的替换目标是<b>确定的字面形状</b>，不需要再解析一遍 DOM。
 * {@code HtmlSanitizerTest} 钉住了这个形状 —— 渲染形式一变，那条断言先红。
 *
 * <h2>签不出地址时保留占位，不删标签</h2>
 * <p>本地存储模式下 {@code inlineSignedUrl} 恒为 {@code empty}
 * （{@code LocalObjectStorage} 没有签名地址，这是有意的）。此时保留原样：
 * 浏览器显示一张坏图，<b>看得见</b>；删掉标签则是静默丢内容 —— 接口 200、正文少了一张图，
 * 没人会发现。
 */
@Component
public class MaterialContentRewriter {

    /** 与 {@link HtmlSanitizer#IMG_SRC_PLACEHOLDER} 同源；改一处必须改另一处。 */
    private static final Pattern PLACEHOLDER_IN_SRC = Pattern.compile(
            "src=\"" + HtmlSanitizer.FILE_PLACEHOLDER_SCHEME + ":([0-9]{1,19})\"");

    private final InlineFileUrlProvider inlineFileUrlProvider;

    public MaterialContentRewriter(InlineFileUrlProvider inlineFileUrlProvider) {
        this.inlineFileUrlProvider = inlineFileUrlProvider;
    }

    /** 出参时调用。{@code null} 原样返回。 */
    public String toResponse(String storedContent) {
        if (storedContent == null || storedContent.isEmpty()) {
            return storedContent;
        }
        Matcher matcher = PLACEHOLDER_IN_SRC.matcher(storedContent);
        StringBuilder out = new StringBuilder(storedContent.length());
        while (matcher.find()) {
            String replacement = matcher.group();
            try {
                Long fileId = Long.valueOf(matcher.group(1));
                String signed = inlineFileUrlProvider.inlineSignedUrl(fileId).orElse(null);
                if (signed != null) {
                    replacement = "src=\"" + escapeAttribute(signed) + "\"";
                }
            } catch (NumberFormatException ignored) {
                // 19 位以内但超出 long 范围 —— 保留占位，见类注释「看得见优于静默丢内容」
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 签名地址进 HTML 属性前必须转义。
     *
     * <p>OSS 签名里有 {@code &} 分隔的查询参数，不转义会在某些解析器下截断 URL；
     * {@code "} 会直接闭合属性 —— 那就是一个由我们自己制造的注入点。
     */
    private static String escapeAttribute(String url) {
        return url.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
