package com.edumatrix.course.catalog.service;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.file.InlineFileUrlProvider;
import com.edumatrix.common.xss.HtmlSanitizer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MaterialContentRewriter}：D-3 定案 —— 正文存 {@code fileId} 占位，出参重写为签名地址。
 *
 * <p>变异验证：把 {@code toResponse} 改成恒等函数 → {@link #rewritesPlaceholderToSignedUrl} 红；
 * 把「签不出就保留占位」改成「删掉标签」→ {@link #keepsPlaceholderWhenUnsigned} 红；
 * 去掉 {@code &} 的转义 → {@link #escapesAmpersandInSignedUrl} 红。
 */
class MaterialContentRewriterTest {

    private static final String SIGNED =
            "https://oss.example.com/k.png?Expires=1&OSSAccessKeyId=x&Signature=abc";

    private static MaterialContentRewriter with(InlineFileUrlProvider provider) {
        return new MaterialContentRewriter(provider);
    }

    @Test
    @DisplayName("占位被重写为签名地址，且原占位不再出现")
    void rewritesPlaceholderToSignedUrl() {
        String stored = "<p>x</p><img src=\"edumxfile:1949600000000000021\">";
        String out = with(id -> Optional.of(SIGNED)).toResponse(stored);
        assertTrue(out.contains("oss.example.com"), out);
        assertFalse(out.contains("edumxfile:"), "占位符原样发给了前端：" + out);
    }

    @Test
    @DisplayName("签不出地址时保留占位、不删标签 —— 坏图看得见，静默丢内容没人会发现")
    void keepsPlaceholderWhenUnsigned() {
        String stored = "<img src=\"edumxfile:1\">";
        String out = with(id -> Optional.empty()).toResponse(stored);
        assertTrue(out.contains("edumxfile:1"), out);
        assertTrue(out.contains("<img"), out);
    }

    @Test
    @DisplayName("签名里的 & 必须转义 —— 不转义会在某些解析器下截断 URL")
    void escapesAmpersandInSignedUrl() {
        String out = with(id -> Optional.of(SIGNED)).toResponse("<img src=\"edumxfile:1\">");
        assertTrue(out.contains("&amp;"), "& 没转义：" + out);
        assertFalse(out.matches("(?s).*[^&]&[^a].*"), "仍有裸 &：" + out);
    }

    @Test
    @DisplayName("多张图各自重写；正文里的普通文本不受影响")
    void rewritesEveryOccurrence() {
        String stored = "<img src=\"edumxfile:1\">中间文字<img src=\"edumxfile:2\">";
        String out = with(id -> Optional.of("https://x/" + id)).toResponse(stored);
        assertTrue(out.contains("https://x/1"), out);
        assertTrue(out.contains("https://x/2"), out);
        assertTrue(out.contains("中间文字"), out);
    }

    @Test
    @DisplayName("重写的目标形状与 HtmlSanitizer 的输出形状同源")
    void sharesShapeWithSanitizer() {
        String sanitized = new HtmlSanitizer().sanitize("<img src='edumxfile:7'>");
        String out = with(id -> Optional.of("https://x/ok")).toResponse(sanitized);
        assertTrue(out.contains("https://x/ok"),
                "净化器的输出没能被重写器识别 —— 两处形状已经分叉：" + sanitized);
    }

    @Test
    @DisplayName("null / 空串原样返回")
    void passesNullAndEmptyThrough() {
        MaterialContentRewriter rewriter = with(id -> Optional.of(SIGNED));
        org.junit.jupiter.api.Assertions.assertNull(rewriter.toResponse(null));
        org.junit.jupiter.api.Assertions.assertEquals("", rewriter.toResponse(""));
    }
}
