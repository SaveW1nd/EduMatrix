package com.edumatrix.common.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * 上传类型判定 —— {@code 00-通用约定} §7.4 第 3 行的落地：
 * 「扩展名 + 请求 {@code Content-Type} + <b>文件头魔数</b>三重校验，
 * <b>以魔数为准且必须与扩展名一致</b>；服务端强制重命名为 {@code {fileId}.{规范扩展名}}」。
 *
 * <h2>两级判定，缺第二级就等于没判</h2>
 * <ol>
 *   <li><b>一级</b>：{@link FileMagic} 的字节签名 → 得到族；</li>
 *   <li><b>二级（族内判别）</b>：ZIP 族与 OLE2 族内部魔数<b>完全相同</b>，
 *       必须再往里看一层，否则「真 {@code .xlsx} 改名 {@code .docx}」会被放行 ——
 *       而验收标准那条「改扩展名伪装的文件被魔数校验拒绝」<b>看起来仍然是绿的</b>。
 *       <ul>
 *         <li>ZIP 族：按 ZIP 条目判 —— {@code word/document.xml} → docx，
 *             {@code xl/workbook.xml} → xlsx，{@code ppt/presentation.xml} → pptx，
 *             三者皆无 → 普通 zip；</li>
 *         <li>OLE2 族：按 POIFS 根目录条目判 —— {@code WordDocument} → doc，
 *             {@code Workbook}/{@code Book} → xls，{@code PowerPoint Document} → ppt。</li>
 *       </ul>
 *   </li>
 * </ol>
 * <p>POI 本来就要为 {@code common/excel/SafeExcelWriter} 引入，二级判别<b>不额外增加依赖</b>。
 *
 * <h2>{@code txt} 没有魔数 —— 必须给它一条<b>可测</b>的规则，否则它是任意二进制的后门</h2>
 * <p>三条与门：
 * <ol>
 *   <li>不匹配 {@link FileMagic} 表里的<b>任何一条</b>签名；</li>
 *   <li>不含 {@code 0x00}（<b>整文件</b>扫，不是只看头部 —— NUL 在 UTF-8 里是合法码点）；</li>
 *   <li>全文可按 UTF-8 <b>或</b> GBK 解码成功。</li>
 * </ol>
 * <p><b>副作用要说清</b>：SVG 是纯文本 XML，按这三条它<b>能以 {@code .txt} 通过</b>。
 * 「SVG 不在白名单」这条基线本身拦不住 SVG <b>内容</b> ——
 * 真正拦住它的是下载时的 {@code Content-Disposition: attachment} + {@code nosniff}
 * （见 {@code ObjectStorage.Disposition}）。两者不可互相顶替。
 *
 * <h2>{@code Content-Type} 只 WARN 不拒绝 —— 这是对 §7.4 的一处<b>有意收窄</b></h2>
 * <p>§7.4 原文对"必须一致"只约束了「魔数 vs 扩展名」，没有对 {@code Content-Type} 提同样要求。
 * 而浏览器（尤其微信内置浏览器 X5/XWEB，PRD §7.4 的一级适配目标）对 {@code .xlsx}
 * 经常上报 {@code application/octet-stream}；把它作硬闸会在真机上大面积误拒。
 * 故本类只在不一致时提供一个可记录的信号，判定不参与。<b>已登记 F-34</b>，需方可推翻。
 */
public final class FileTypeDetector {

    /**
     * 白名单（03-01 §7.1 逐字：{@code jpg/jpeg/png/gif/webp/pdf/doc/docx/xls/xlsx/ppt/pptx/txt/zip}）。
     *
     * <p>值是<b>规范扩展名</b>：{@code jpeg} 归一到 {@code jpg}，
     * 因为强制重命名只能有一个写法，两种并存会让同一张图在库里出现两种 {@code file_type}。
     */
    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("jpg", "jpg"), Map.entry("jpeg", "jpg"),
            Map.entry("png", "png"), Map.entry("gif", "gif"), Map.entry("webp", "webp"),
            Map.entry("pdf", "pdf"),
            Map.entry("doc", "doc"), Map.entry("docx", "docx"),
            Map.entry("xls", "xls"), Map.entry("xlsx", "xlsx"),
            Map.entry("ppt", "ppt"), Map.entry("pptx", "pptx"),
            Map.entry("txt", "txt"), Map.entry("zip", "zip"));

    /** 规范扩展名 → MIME。用于 OSS 对象元数据与 D-2 内联签名地址的 {@code response-content-type}。 */
    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt", "text/plain"),
            Map.entry("zip", "application/zip"));

    /** 流式解码的定长缓冲。见 {@code looksLikePlainText} 对内存上界的说明。 */
    private static final int DECODE_BUFFER_BYTES = 8192;

    /** 图片档 —— 对应 03-01 §7.1「单文件上限 100MB（<b>图片 10MB</b>）」。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "png", "gif", "webp");

    private FileTypeDetector() {
    }

    /** 判定结果。{@code canonicalExt} 是<b>魔数判出来的</b>，不是用户给的那个。 */
    public record DetectedType(String canonicalExt, String mimeType) {

        /** 是否落在图片档（决定 10MB 还是 100MB）。 */
        public boolean isImage() {
            return IMAGE_EXTENSIONS.contains(canonicalExt);
        }
    }

    /** 扩展名是否在白名单内（大小写不敏感、可带点）。 */
    public static boolean isWhitelisted(String extension) {
        return canonical(extension).isPresent();
    }

    /** 归一化扩展名（{@code .JPEG} → {@code jpg}）。不在白名单返回 {@code empty}。 */
    public static Optional<String> canonical(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return Optional.ofNullable(CANONICAL.get(normalized));
    }

    /**
     * 规范扩展名 → MIME。用于 OSS 对象元数据与 D-2 内联签名地址的 {@code response-content-type}。
     *
     * <p>不在白名单时返回 {@code empty}，调用方一律回落 {@code application/octet-stream} ——
     * <b>猜一个 MIME 比不知道更危险</b>：猜成 {@code text/html} 就等于把一个存储桶变成了 XSS 面。
     */
    public static Optional<String> mimeOf(String canonicalExt) {
        return canonical(canonicalExt).map(MIME::get);
    }

    /** 从原始文件名取扩展名（无点则返回空串，随后判定失败）。 */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1);
    }

    /**
     * 按<b>内容</b>判定真实类型。判不出（不在白名单能表达的范围内）返回 {@code empty}。
     *
     * <p>调用方随后必须再比一次「判定结果 == 声明扩展名的规范化形式」，不一致 → {@code 10011}。
     * 本方法<b>不做那次比较</b>，是为了让"检测"与"策略"分开：
     * 前者可以逐族单测，后者在 {@code FileService} 里与大小、bizType 一起排成判定顺序表。
     */
    public static Optional<DetectedType> detect(Path file) throws IOException {
        byte[] head = readHead(file);
        Optional<String> family = FileMagic.detectFamily(head);

        if (family.isEmpty()) {
            // 没有魔数 —— 只可能是 txt，且必须过三条与门
            return looksLikePlainText(file)
                    ? Optional.of(typeOf("txt"))
                    : Optional.empty();
        }

        String matched = family.get();
        if (FileMagic.FAMILY_ZIP.equals(matched)) {
            return Optional.of(typeOf(discriminateZipFamily(file)));
        }
        if (FileMagic.FAMILY_OLE2.equals(matched)) {
            return discriminateOle2Family(file).map(FileTypeDetector::typeOf);
        }
        // jpg / png / gif / webp / pdf —— 族名即规范扩展名
        return Optional.of(typeOf(matched));
    }

    private static DetectedType typeOf(String canonicalExt) {
        return new DetectedType(canonicalExt, MIME.get(canonicalExt));
    }

    // ====================================================================
    // 二级：族内判别
    // ====================================================================

    /**
     * ZIP 族内判别。<b>拿不准就退回 {@code zip}</b> —— 这样一个真 xlsx 声明为 docx 时，
     * 判定结果（xlsx 或 zip）与声明（docx）都不相等，一律被拒。
     */
    private static String discriminateZipFamily(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            boolean word = false;
            boolean excel = false;
            boolean powerPoint = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("word/")) {
                    word = true;
                } else if (name.startsWith("xl/")) {
                    excel = true;
                } else if (name.startsWith("ppt/")) {
                    powerPoint = true;
                }
            }
            if (word && !excel && !powerPoint) {
                return "docx";
            }
            if (excel && !word && !powerPoint) {
                return "xlsx";
            }
            if (powerPoint && !word && !excel) {
                return "pptx";
            }
            return "zip";
        } catch (IOException e) {
            // 打不开的 ZIP：按普通 zip 处理。声明为 docx/xlsx/pptx 时照样被拒
            return "zip";
        }
    }

    /**
     * OLE2 族内判别，按 POIFS 根目录的流名。
     *
     * <p>{@code xls} 有两个历史流名：Excel 97+ 是 {@code Workbook}，
     * 更早的 Excel 5.0/95 是 {@code Book}。只判前者会把老 xls 判成"判不出"，
     * 从而以一句「文件类型不支持」拒掉一个合法文件。
     */
    private static Optional<String> discriminateOle2Family(Path file) {
        try (InputStream in = Files.newInputStream(file);
             POIFSFileSystem poifs = new POIFSFileSystem(in)) {
            Set<String> entries = poifs.getRoot().getEntryNames();
            if (entries.contains("WordDocument")) {
                return Optional.of("doc");
            }
            if (entries.contains("Workbook") || entries.contains("Book")) {
                return Optional.of("xls");
            }
            if (entries.contains("PowerPoint Document")) {
                return Optional.of("ppt");
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ====================================================================
    // txt 的三条与门
    // ====================================================================

    /**
     * txt 的三条与门（第 1 条"不匹配任何魔数"由调用方保证）。
     *
     * <p><b>整文件流式扫描，不是只看头 512 字节</b>。这一点被一条测试逼出来过：
     * 起初 NUL 检查只在头部做，而 <b>{@code 0x00} 在 UTF-8 里是一个完全合法的码点</b>，
     * 于是「前 1500 字节是 'A'、第 1500 字节放一个 NUL」的构造<b>两条都过</b>——
     * txt 这条通道当场变成任意二进制的后门。
     * {@code FileTypeDetectorTest#textHeaderWithBinaryTailIsRejected} 就是那条用例。
     *
     * <p><b>流式而不是 {@code readAllBytes}</b>：单文件上限 100MB，而生产是单实例
     * {@code -Xmx1g}（{@code edumatrix.service}）。整读进内存再解码，
     * 峰值是「字节数组 + CharBuffer」两三百 MB，几个并发上传就能把堆吃穿 ——
     * 而 OOM 的表现是整个进程被 {@code -XX:+ExitOnOutOfMemoryError} 干掉，
     * 一次上传拖垮全站。这里用 8KB 定长缓冲，内存与文件大小无关。
     */
    private static boolean looksLikePlainText(Path file) {
        return isPlainTextIn(file, java.nio.charset.StandardCharsets.UTF_8)
                || isPlainTextIn(file, Charset.forName("GBK"));
    }

    /** 单次流式扫描：既查 {@code 0x00}，又做严格解码。 */
    private static boolean isPlainTextIn(Path file, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.allocate(DECODE_BUFFER_BYTES);
        CharBuffer out = CharBuffer.allocate(DECODE_BUFFER_BYTES);

        try (ReadableByteChannel channel = Files.newByteChannel(file)) {
            boolean eof = false;
            while (!eof) {
                eof = channel.read(in) < 0;
                in.flip();
                for (int i = in.position(); i < in.limit(); i++) {
                    if (in.get(i) == 0) {
                        return false;
                    }
                }
                if (!decodeInto(decoder, in, out, eof)) {
                    return false;
                }
                in.compact();
            }
            out.clear();
            return !decoder.flush(out).isError();
        } catch (IOException e) {
            return false;
        }
    }

    /** 把 {@code in} 解到 {@code out}；OVERFLOW 不是错误（缓冲满了而已），清空重来。 */
    private static boolean decodeInto(CharsetDecoder decoder, ByteBuffer in, CharBuffer out, boolean eof) {
        while (true) {
            CoderResult result = decoder.decode(in, out, eof);
            if (result.isError()) {
                return false;
            }
            if (result.isOverflow()) {
                out.clear();
                continue;
            }
            return true;
        }
    }

    private static byte[] readHead(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(FileMagic.HEAD_LENGTH);
        }
    }
}
