package com.edumatrix.auth.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * 验证码图片渲染 —— <b>纯笔画自绘，不使用 {@code java.awt.Font}</b>。
 *
 * <h2>为什么不用字体</h2>
 * <p>生产是 Docker，精简基础镜像（尤其 alpine）<b>不带 fontconfig，也不带任何字体文件</b>，
 * {@code Font} 渲染会抛异常或画出空白图。而这在 macOS / Windows 开发机上<b>永远不会复现</b> ——
 * 本机字体齐全，测试全绿，上线才炸，且现象是「验证码是一张空白图」，
 * 排查时很难第一时间想到字体。
 *
 * <p>另一条路是在 Dockerfile 里装 fontconfig + 一套字体，但仓库里<b>目前没有 Dockerfile</b>
 * （{@code deploy/} 下只有 {@code docker-compose.dev.yml}），
 * 新建一个生产镜像定义超出模块 02 的范围。故选这条：<b>把依赖去掉，问题就不存在</b>。
 *
 * <h2>怎么画</h2>
 * <p>14 段「米」字数码管：每个字符是一组直线段的开关组合，坐标由字符框宽高算出。
 * 这是数码显示器用了几十年的方案，字形辨识度有保证，而且总共只有 14 条线段要定义。
 *
 * <pre>
 *   TL --a-- TM --a-- TR        竖 / 横：a b c d e f g1 g2
 *    |\      |i     /|          斜 / 中竖：h i j k l m
 *    f h     |    j  b
 *    |  \    |   /   |
 *   ML --g1--C--g2-- MR
 *    |  /    |   \   |
 *    e m     |l    k c
 *    |/      |      \|
 *   BL --d-- BM --d-- BR
 * </pre>
 *
 * <h2>字母表剔除了形近字</h2>
 * <p>验证码「不区分大小写」（03-01 §1.1），所以只要形状撞了就是歧义。剔除：
 * {@code 0/O}、{@code 1/I}（天生形近）；{@code 5/S}（14 段编码<b>完全相同</b>）；
 * {@code 6/G}（只差一段）；{@code 8/B}（只差竖线）。剩下 29 个字符全部两两可辨。
 */
public class CaptchaImageRenderer {

    /** 可用字符集：剔除形近字后的 29 个（见类注释）。 */
    public static final String ALPHABET = "23456789ACDEFHJKLMNPQRTUVWXYZ";

    private static final int IMAGE_WIDTH = 130;
    private static final int IMAGE_HEIGHT = 44;
    private static final int GLYPH_WIDTH = 18;
    private static final int GLYPH_HEIGHT = 26;

    /** 深色字符色板 —— 与浅色背景保证对比度，同时避免纯黑的机械感。 */
    private static final Color[] INK_COLORS = {
            new Color(0x1F3A93), new Color(0x8E2B2B), new Color(0x1E6B52),
            new Color(0x5B3A87), new Color(0x8A5A00), new Color(0x2C3E50),
    };

    private static final Map<Character, String[]> GLYPHS = buildGlyphs();

    private final SecureRandom random = new SecureRandom();

    /**
     * 画出验证码图，返回 {@code data:image/png;base64,...} 形式的 Data URI
     * （03-01 §1.1 的 {@code captchaImage} 字段，前端直接作为 {@code <img src>}）。
     */
    public String renderAsDataUri(String code) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            paintBackground(g);
            paintNoiseLines(g);
            paintCode(g, code);
            paintNoiseDots(image);
        } finally {
            g.dispose();
        }
        return "data:image/png;base64," + toBase64Png(image);
    }

    /** 随机取一个验证码原文（4 位，03-01 §1.1）。 */
    public String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    // =====================================================================

    private void paintBackground(Graphics2D g) {
        g.setColor(new Color(0xF3F5F9));
        g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
    }

    /** 干扰线：跨全图的浅色折线，防止按连通域直接切字符。 */
    private void paintNoiseLines(Graphics2D g) {
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 5; i++) {
            g.setColor(new Color(
                    150 + random.nextInt(80), 150 + random.nextInt(80), 150 + random.nextInt(80)));
            g.draw(new Line2D.Double(
                    random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT),
                    random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT)));
        }
    }

    /** 逐字符画：每个字符独立的旋转、位移与颜色，避免整串可被同一模板匹配。 */
    private void paintCode(Graphics2D g, String code) {
        int step = (IMAGE_WIDTH - 16) / Math.max(code.length(), 1);
        for (int i = 0; i < code.length(); i++) {
            String[] segments = GLYPHS.get(Character.toUpperCase(code.charAt(i)));
            if (segments == null) {
                continue;
            }
            AffineTransform saved = g.getTransform();
            double x = 10 + i * step + random.nextInt(5) - 2;
            double y = 8 + random.nextInt(5) - 2;
            g.translate(x, y);
            // ±14° 的随机倾斜，绕字符中心转
            g.rotate(Math.toRadians(random.nextInt(29) - 14), GLYPH_WIDTH / 2.0, GLYPH_HEIGHT / 2.0);
            g.setColor(INK_COLORS[random.nextInt(INK_COLORS.length)]);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (String segment : segments) {
                g.draw(segmentLine(segment));
            }
            g.setTransform(saved);
        }
    }

    /** 椒盐噪点：直接改像素，避免与线段共用图元特征。 */
    private void paintNoiseDots(BufferedImage image) {
        for (int i = 0; i < 120; i++) {
            image.setRGB(random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT),
                    new Color(random.nextInt(160), random.nextInt(160), random.nextInt(160)).getRGB());
        }
    }

    /** 段名 → 线段坐标（见类注释的示意图）。 */
    private static Line2D segmentLine(String segment) {
        double w = GLYPH_WIDTH;
        double h = GLYPH_HEIGHT;
        double mx = w / 2;
        double my = h / 2;
        return switch (segment) {
            case "a" -> new Line2D.Double(0, 0, w, 0);
            case "b" -> new Line2D.Double(w, 0, w, my);
            case "c" -> new Line2D.Double(w, my, w, h);
            case "d" -> new Line2D.Double(0, h, w, h);
            case "e" -> new Line2D.Double(0, my, 0, h);
            case "f" -> new Line2D.Double(0, 0, 0, my);
            case "g1" -> new Line2D.Double(0, my, mx, my);
            case "g2" -> new Line2D.Double(mx, my, w, my);
            case "h" -> new Line2D.Double(0, 0, mx, my);
            case "i" -> new Line2D.Double(mx, 0, mx, my);
            case "j" -> new Line2D.Double(w, 0, mx, my);
            case "k" -> new Line2D.Double(mx, my, w, h);
            case "l" -> new Line2D.Double(mx, my, mx, h);
            case "m" -> new Line2D.Double(mx, my, 0, h);
            default -> throw new IllegalArgumentException("未知的字形段：" + segment);
        };
    }

    private static Map<Character, String[]> buildGlyphs() {
        Map<Character, String[]> map = new LinkedHashMap<>();
        map.put('2', new String[]{"a", "b", "g1", "g2", "e", "d"});
        map.put('3', new String[]{"a", "b", "g1", "g2", "c", "d"});
        map.put('4', new String[]{"f", "g1", "g2", "b", "c"});
        map.put('5', new String[]{"a", "f", "g1", "g2", "c", "d"});
        map.put('6', new String[]{"a", "f", "e", "d", "c", "g1", "g2"});
        map.put('7', new String[]{"a", "b", "c"});
        map.put('8', new String[]{"a", "b", "c", "d", "e", "f", "g1", "g2"});
        map.put('9', new String[]{"a", "b", "c", "d", "f", "g1", "g2"});
        map.put('A', new String[]{"a", "b", "c", "e", "f", "g1", "g2"});
        map.put('C', new String[]{"a", "f", "e", "d"});
        map.put('D', new String[]{"a", "b", "c", "d", "i", "l"});
        map.put('E', new String[]{"a", "f", "e", "d", "g1", "g2"});
        map.put('F', new String[]{"a", "f", "e", "g1", "g2"});
        map.put('H', new String[]{"b", "c", "e", "f", "g1", "g2"});
        map.put('J', new String[]{"b", "c", "d", "e"});
        map.put('K', new String[]{"f", "e", "g1", "j", "k"});
        map.put('L', new String[]{"f", "e", "d"});
        map.put('M', new String[]{"e", "f", "h", "j", "b", "c"});
        map.put('N', new String[]{"e", "f", "h", "k", "b", "c"});
        map.put('P', new String[]{"a", "b", "e", "f", "g1", "g2"});
        map.put('Q', new String[]{"a", "b", "c", "d", "e", "f", "k"});
        map.put('R', new String[]{"a", "b", "e", "f", "g1", "g2", "k"});
        map.put('T', new String[]{"a", "i", "l"});
        map.put('U', new String[]{"b", "c", "d", "e", "f"});
        map.put('V', new String[]{"f", "e", "m", "j"});
        map.put('W', new String[]{"f", "e", "m", "k", "b", "c"});
        map.put('X', new String[]{"h", "j", "k", "m"});
        map.put('Y', new String[]{"h", "j", "l"});
        map.put('Z', new String[]{"a", "j", "m", "d"});
        return map;
    }

    private static String toBase64Png(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(4096)) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("验证码图片编码失败", e);
        }
    }
}
