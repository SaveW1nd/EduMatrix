package com.edumatrix.auth.service;

import java.awt.GraphicsEnvironment;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证码渲染的单元测试 —— 盯的是<b>一个只会在生产暴露的故障</b>。
 *
 * <p>精简 Docker 镜像（尤其 alpine）不带 fontconfig 与任何字体文件，
 * {@code java.awt.Font} 在那里会抛异常或画出空白图，<b>而在开发机上永远不复现</b>。
 * 本渲染器因此完全不用字体（14 段笔画自绘），这个测试就是那条约束的守门人：
 * 它强制 headless，并断言产出的确实是一张有内容的 PNG。
 */
class CaptchaImageRendererTest {

    private final CaptchaImageRenderer renderer = new CaptchaImageRenderer();

    @Test
    @DisplayName("headless 环境下能画出非空 PNG（不依赖任何字体）")
    void rendersWithoutFonts() {
        System.setProperty("java.awt.headless", "true");
        assertThat(GraphicsEnvironment.isHeadless())
                .as("生产就是 headless，测试必须在同样的模式下验")
                .isTrue();

        String dataUri = renderer.renderAsDataUri("8KT6");

        assertThat(dataUri).startsWith("data:image/png;base64,");
        byte[] png = Base64.getDecoder().decode(dataUri.substring("data:image/png;base64,".length()));
        assertThat(png).hasSizeGreaterThan(500);
        // PNG 魔数：89 50 4E 47
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");
    }

    @Test
    @DisplayName("字母表里每个字符都有字形，且剔除了形近字")
    void everyAlphabetCharHasGlyph() {
        // 每个字符都能画（画不出来的字符会在 segmentLine 里抛 IllegalArgumentException）
        for (char c : CaptchaImageRenderer.ALPHABET.toCharArray()) {
            assertThat(renderer.renderAsDataUri(String.valueOf(c))).isNotBlank();
        }

        Set<Character> chars = new HashSet<>();
        for (char c : CaptchaImageRenderer.ALPHABET.toCharArray()) {
            assertThat(chars.add(c)).as("字母表不得重复：" + c).isTrue();
        }
        assertThat(chars)
                .as("验证码不区分大小写，形状撞了就是歧义：0/O、1/I 天生形近；"
                        + "5/S 的 14 段编码完全相同；6/G 只差一段；8/B 只差竖线")
                .doesNotContain('0', 'O', '1', 'I', 'S', 'G', 'B');
    }

    @Test
    @DisplayName("随机码长度正确且只用字母表内的字符")
    void randomCodeUsesAlphabetOnly() {
        for (int i = 0; i < 50; i++) {
            String code = renderer.randomCode(4);
            assertThat(code).hasSize(4);
            for (char c : code.toCharArray()) {
                assertThat(CaptchaImageRenderer.ALPHABET).contains(String.valueOf(c));
            }
        }
    }
}
