package com.edumatrix.common.xss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HtmlSanitizer}：PRD F2-2 规则 1「服务端 XSS 白名单过滤」+ 验收标准第 2 条。
 *
 * <h2>为什么<b>剥离侧</b>与<b>保留侧</b>都要断言</h2>
 * <p>只测「{@code <script>} 没了」的话，把 {@code sanitize} 换成 {@code s -> ""}
 * 依然全绿 —— 那正是本项目说的「绿灯不是证据」。保留侧的断言让恒空实现立刻红。
 *
 * <h2>本类的变异验证（已实测）</h2>
 * <ul>
 *   <li>把 {@code sanitize} 改成恒等函数（{@code return rawHtml;}）→
 *       {@link #stripsScriptTag} 等 5 条红；
 *   <li>把 {@code sanitize} 改成恒空（{@code return "";}）→
 *       {@link #keepsWhitelistedMarkup} 等 3 条红；
 *   <li>把 {@code <img src>} 的正则谓词去掉、改成放行任意 URL →
 *       {@link #dropsExternalImageSource} 红。
 * </ul>
 */
class HtmlSanitizerTest {

    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    // =====================================================================
    // 剥离侧
    // =====================================================================

    @Test
    @DisplayName("PRD F2-2 验收标准 2：<script> 被过滤后才落库")
    void stripsScriptTag() {
        String out = sanitizer.sanitize("<p>正文</p><script>alert(1)</script>");
        assertFalse(out.toLowerCase().contains("script"), "输出仍含 script：" + out);
        assertTrue(out.contains("正文"), "正文被一并吃掉了：" + out);
    }

    @Test
    @DisplayName("on* 事件属性被剥离（正则剥标签拦不住的那一类）")
    void stripsEventHandlers() {
        String out = sanitizer.sanitize("<img src=\"edumxfile:1\" onerror=\"alert(1)\">");
        assertFalse(out.toLowerCase().contains("onerror"), out);
        assertTrue(out.contains("edumxfile:1"), "合法占位被误杀：" + out);
    }

    @Test
    @DisplayName("javascript: 协议的链接被剥离")
    void stripsJavascriptHref() {
        String out = sanitizer.sanitize("<a href=\"javascript:alert(1)\">点我</a>");
        assertFalse(out.toLowerCase().contains("javascript:"), out);
    }

    @Test
    @DisplayName("iframe / svg / style 属性一律不在白名单内")
    void stripsDangerousElementsAndStyle() {
        String out = sanitizer.sanitize(
                "<iframe src=\"https://evil\"></iframe><svg onload=\"alert(1)\"></svg>"
                        + "<div style=\"width:expression(alert(1))\">x</div>");
        assertFalse(out.toLowerCase().contains("iframe"), out);
        assertFalse(out.toLowerCase().contains("svg"), out);
        assertFalse(out.toLowerCase().contains("style"), out);
        assertTrue(out.contains("x"), "文本被一并吃掉了：" + out);
    }

    @Test
    @DisplayName("D-3：<img src> 只接受 edumxfile 占位，外链一律剥掉（PRD F2-2 规则 1「不允许外链」）")
    void dropsExternalImageSource() {
        String out = sanitizer.sanitize("<img src=\"https://cdn.example.com/a.png\">");
        assertFalse(out.contains("cdn.example.com"), "外链地址进了正文：" + out);
    }

    // =====================================================================
    // 保留侧 —— 没有这几条，恒空实现也能全绿
    // =====================================================================

    @Test
    @DisplayName("PRD F2-2 规则 1 要的四样：图片、加粗、列表、表格全部保留")
    void keepsWhitelistedMarkup() {
        String out = sanitizer.sanitize("<h2>标题</h2><p><strong>加粗</strong></p>"
                + "<ul><li>一</li><li>二</li></ul>"
                + "<table><tr><th colspan=\"2\">表头</th></tr><tr><td>格</td></tr></table>"
                + "<img src=\"edumxfile:1949600000000000021\" alt=\"图\">");
        assertTrue(out.contains("<strong>加粗</strong>"), out);
        assertTrue(out.contains("<li>一</li>"), out);
        assertTrue(out.contains("colspan=\"2\""), out);
        assertTrue(out.contains("<h2>标题</h2>"), out);
        assertTrue(out.contains("alt=\"图\""), out);
    }

    @Test
    @DisplayName("合法的 https 外链保留，并被强制加上 rel=nofollow")
    void keepsHttpsLinkWithNofollow() {
        String out = sanitizer.sanitize("<a href=\"https://example.com\">站外</a>");
        assertTrue(out.contains("https://example.com"), out);
        assertTrue(out.contains("nofollow"), "缺 rel=nofollow：" + out);
    }

    @Test
    @DisplayName("null 原样返回（content 列可空）")
    void passesNullThrough() {
        org.junit.jupiter.api.Assertions.assertNull(sanitizer.sanitize(null));
    }

    // =====================================================================
    // 输出形状 —— MaterialContentRewriter 的正则依赖它
    // =====================================================================

    @Test
    @DisplayName("渲染形状被钉死：src 恒为双引号属性，这是 MaterialContentRewriter 敢用正则的前提")
    void rendersAttributesWithDoubleQuotes() {
        String out = sanitizer.sanitize("<img src='edumxfile:123'>");
        assertTrue(out.contains("src=\"edumxfile:123\""),
                "渲染形式变了 —— MaterialContentRewriter 的正则会静默失配，"
                        + "正文里的占位符将原样发给前端。实际输出：" + out);
    }

    @Test
    @DisplayName("占位符正则只接受 1~19 位数字")
    void placeholderPatternBounds() {
        assertTrue(HtmlSanitizer.IMG_SRC_PLACEHOLDER.matcher("edumxfile:1").matches());
        assertTrue(HtmlSanitizer.IMG_SRC_PLACEHOLDER.matcher("edumxfile:1949600000000000021").matches());
        assertFalse(HtmlSanitizer.IMG_SRC_PLACEHOLDER.matcher("edumxfile:").matches());
        assertFalse(HtmlSanitizer.IMG_SRC_PLACEHOLDER.matcher("edumxfile:12345678901234567890").matches());
        assertFalse(HtmlSanitizer.IMG_SRC_PLACEHOLDER.matcher("https://x").matches());
        assertEquals("edumxfile", HtmlSanitizer.FILE_PLACEHOLDER_SCHEME);
    }
}
