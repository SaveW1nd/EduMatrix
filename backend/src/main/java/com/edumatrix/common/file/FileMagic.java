package com.edumatrix.common.file;

import java.util.List;
import java.util.Optional;

/**
 * 文件头魔数表（<b>第一级</b>判定）。手写常量 + 字节比较，无外部依赖。
 *
 * <h2>为什么不引 Apache Tika</h2>
 * <p>Tika 的 detect 也只到<b>族</b>级（{@code application/x-tika-ooxml}），仍要做第二级判别；
 * 而它带来约 20MB 依赖与一套需要跟版本维护的 mime 库。
 * 白名单只有 14 个扩展名，手写表约 100 行、<b>每一行都能被单测覆盖</b>。
 *
 * <h2>⚠ 「以魔数为准且必须与扩展名一致」在这 14 个扩展名上，字面执行不了</h2>
 * <table border="1">
 *   <caption>两个族内部魔数完全相同</caption>
 *   <tr><th>族</th><th>成员</th><th>魔数</th></tr>
 *   <tr><td>ZIP</td><td>{@code docx} {@code xlsx} {@code pptx} {@code zip}</td>
 *       <td>{@code 50 4B 03 04} —— <b>完全相同</b></td></tr>
 *   <tr><td>OLE2</td><td>{@code doc} {@code xls} {@code ppt}</td>
 *       <td>{@code D0 CF 11 E0 A1 B1 1A E1} —— <b>完全相同</b></td></tr>
 *   <tr><td>纯文本</td><td>{@code txt}</td><td><b>没有魔数</b></td></tr>
 * </table>
 * <p>只做到这一级就收工的话，「把 {@code .xlsx} 改名成 {@code .docx} 上传」<b>会被放行</b>，
 * 而验收标准那条「改扩展名伪装的文件被魔数校验拒绝」看起来仍然是绿的 ——
 * 正是「绿灯不是证据」那一族。族内判别在 {@link FileTypeDetector}。
 *
 * <p><b>SVG 不在本表也不在白名单</b>（{@code 00-通用约定} §7.4 逐字）。但要说清：
 * SVG 是纯文本 XML，它<b>能以 {@code .txt} 通过</b>（见 {@link FileTypeDetector} 的 txt 三条规则）。
 * 真正拦住 SVG 被当作图片渲染的是<b>下载时的 {@code Content-Disposition: attachment}
 * + {@code nosniff}</b>，不是这张表。把「SVG 不在白名单」当成 XSS 防线是一次误解。
 */
public final class FileMagic {

    /** 一条魔数记录：从 {@code offset} 起匹配 {@code signature}。 */
    public record Signature(String family, int offset, byte[] signature, byte[] trailer, int trailerOffset) {

        static Signature of(String family, int... unsignedBytes) {
            return new Signature(family, 0, toBytes(unsignedBytes), null, 0);
        }

        /** RIFF 容器：头 4 字节 {@code RIFF}，第 8 字节起是具体格式（{@code WEBP}）。 */
        static Signature riff(String family, String formatAtOffset8) {
            return new Signature(family, 0, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    formatAtOffset8.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 8);
        }

        boolean matches(byte[] head) {
            if (!regionMatches(head, offset, signature)) {
                return false;
            }
            return trailer == null || regionMatches(head, trailerOffset, trailer);
        }

        private static boolean regionMatches(byte[] head, int at, byte[] expected) {
            if (head.length < at + expected.length) {
                return false;
            }
            for (int i = 0; i < expected.length; i++) {
                if (head[at + i] != expected[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** ZIP 族：{@code docx} / {@code xlsx} / {@code pptx} / {@code zip} 共用。 */
    public static final String FAMILY_ZIP = "zip-family";

    /** OLE2（复合文档）族：{@code doc} / {@code xls} / {@code ppt} 共用。 */
    public static final String FAMILY_OLE2 = "ole2-family";

    /** 读多少字节做一级判定。所有签名都落在前 16 字节内，取 512 是留余量。 */
    public static final int HEAD_LENGTH = 512;

    /**
     * 魔数表。<b>顺序即优先级</b>，但目前各条互不重叠。
     *
     * <p>ZIP 收两个签名：{@code 50 4B 03 04} 是正常档案；{@code 50 4B 05 06} 是
     * <b>空档案</b>（end-of-central-directory 直接打头）。不收后者的话，
     * 一个合法的空 zip 会被判成"没有魔数"→ 落进 txt 分支 → 因含控制字节被拒，
     * 报出来的错是「类型不支持」而不是「文件是空的」，排查时会指向错误的方向。
     */
    private static final List<Signature> SIGNATURES = List.of(
            Signature.of("jpg", 0xFF, 0xD8, 0xFF),
            Signature.of("png", 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            Signature.of("gif", 'G', 'I', 'F', '8', '7', 'a'),
            Signature.of("gif", 'G', 'I', 'F', '8', '9', 'a'),
            Signature.riff("webp", "WEBP"),
            Signature.of("pdf", '%', 'P', 'D', 'F', '-'),
            Signature.of(FAMILY_ZIP, 0x50, 0x4B, 0x03, 0x04),
            Signature.of(FAMILY_ZIP, 0x50, 0x4B, 0x05, 0x06),
            Signature.of(FAMILY_OLE2, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1));

    private FileMagic() {
    }

    /**
     * 一级判定：返回族名（{@code jpg} / {@code png} / {@code gif} / {@code webp} /
     * {@code pdf} / {@link #FAMILY_ZIP} / {@link #FAMILY_OLE2}），无匹配返回 {@code empty}。
     *
     * <p>无匹配<b>不等于</b>非法 —— {@code txt} 本来就没有魔数，由
     * {@link FileTypeDetector} 的三条 txt 规则接手。
     */
    public static Optional<String> detectFamily(byte[] head) {
        for (Signature signature : SIGNATURES) {
            if (signature.matches(head)) {
                return Optional.of(signature.family());
            }
        }
        return Optional.empty();
    }

    private static byte[] toBytes(int[] unsigned) {
        byte[] bytes = new byte[unsigned.length];
        for (int i = 0; i < unsigned.length; i++) {
            bytes[i] = (byte) unsigned[i];
        }
        return bytes;
    }
}
