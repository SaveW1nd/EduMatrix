package com.edumatrix.common.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 上传类型判定 —— {@code 00-通用约定} §7.4 第 3 行「以魔数为准且必须与扩展名一致」的验收。
 *
 * <h2>这一组的关键是 {@link #realXlsxIsNotMistakenForDocx}</h2>
 * <p>只测「PNG 改名 .pdf 被拒」是<b>测不出</b>问题的：那是跨族，一级魔数就拦住了。
 * 而 ZIP 族（docx/xlsx/pptx/zip）与 OLE2 族（doc/xls/ppt）<b>族内魔数完全相同</b>，
 * 只做一级判定时「真 .xlsx 改名 .docx」会被<b>放行</b>，
 * 而验收标准那条「改扩展名伪装的文件被魔数校验拒绝」<b>看起来仍然是绿的</b>。
 * 把 {@code FileTypeDetector} 的族内判别删掉 → 本类的族内两条立刻红。
 *
 * <h2>保留侧</h2>
 * <p>{@link #realFilesKeepTheirOwnType} 钉住"正常文件判得对"——
 * 没有它，把 {@code detect} 改成「一律返回 empty」也会让全部攻击侧用例变绿。
 */
class FileTypeDetectorTest {

    @TempDir
    Path tempDir;

    private Path write(String name, byte[] content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.write(path, content);
        return path;
    }

    private static byte[] png() {
        // PNG 签名 + 一点填充；判定只看头 8 字节
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] out = new byte[64];
        System.arraycopy(head, 0, out, 0, head.length);
        return out;
    }

    private static byte[] xlsx() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("s").createRow(0).createCell(0).setCellValue("x");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] xls() throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("s").createRow(0).createCell(0).setCellValue("x");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] plainZip() throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("hello".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        }
    }

    // ====================================================================
    // 攻击侧
    // ====================================================================

    @Test
    @DisplayName("T-A 跨族伪装：真 PNG 声明为 .pdf → 判定结果与声明不一致")
    void pngRenamedToPdfIsDetectedAsPng() throws IOException {
        Optional<FileTypeDetector.DetectedType> detected = FileTypeDetector.detect(write("x.pdf", png()));

        assertThat(detected).isPresent();
        assertThat(detected.get().canonicalExt()).isEqualTo("png");
        assertThat(detected.get().canonicalExt())
                .as("与声明的 pdf 不一致 → FileService 会拒 10011")
                .isNotEqualTo(FileTypeDetector.canonical("pdf").orElseThrow());
    }

    @Test
    @DisplayName("T-B 【族内伪装】真 .xlsx 声明为 .docx —— 只做一级魔数判定时本条会红")
    void realXlsxIsNotMistakenForDocx() throws IOException {
        Optional<FileTypeDetector.DetectedType> detected = FileTypeDetector.detect(write("payload.docx", xlsx()));

        assertThat(detected).isPresent();
        assertThat(detected.get().canonicalExt())
                .as("ZIP 族魔数与 docx 完全相同（50 4B 03 04），必须靠 ZIP 条目判别才分得开")
                .isEqualTo("xlsx");
    }

    @Test
    @DisplayName("T-B' 【族内伪装】真 .xls 声明为 .doc —— OLE2 族魔数同样完全相同")
    void realXlsIsNotMistakenForDoc() throws IOException {
        Optional<FileTypeDetector.DetectedType> detected = FileTypeDetector.detect(write("payload.doc", xls()));

        assertThat(detected).isPresent();
        assertThat(detected.get().canonicalExt())
                .as("OLE2 族魔数 D0 CF 11 E0 A1 B1 1A E1 三种格式共用，必须读 POIFS 根条目")
                .isEqualTo("xls");
    }

    @Test
    @DisplayName("T-C 二进制改名 .txt：含 NUL → 判不出（txt 三条与门的第 2 条）")
    void binaryRenamedToTxtIsRejected() throws IOException {
        byte[] elf = {0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01, 0x00, 0x00, 0x00};

        assertThat(FileTypeDetector.detect(write("payload.txt", elf)))
                .as("txt 是白名单里唯一没有魔数的类型，它就是这条通道上唯一的闸")
                .isEmpty();
    }

    @Test
    @DisplayName("T-C' 前 512 字节是文本、后面塞 NUL 的构造同样被拒（整文件解码，不只看头部）")
    void textHeaderWithBinaryTailIsRejected() throws IOException {
        byte[] payload = new byte[2048];
        java.util.Arrays.fill(payload, (byte) 'A');
        payload[1500] = 0x00;

        assertThat(FileTypeDetector.detect(write("sneaky.txt", payload)))
                .as("只解码头部的话，把二进制藏在 512 字节之后就能绕过")
                .isEmpty();
    }

    @Test
    @DisplayName("SVG 不在白名单 —— 但它能以 .txt 通过，拦住它的是下载头不是这张表")
    void svgPassesAsTxtAndThatIsDocumentedNotAccidental() throws IOException {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        // 声明 .svg：扩展名压根不在白名单，FileService 在落临时文件之前就拒了
        assertThat(FileTypeDetector.canonical("svg")).isEmpty();

        // 声明 .txt：内容是纯文本，判定通过。这是【已知且有意】的 ——
        // 真正阻止它被当作图片渲染的是 Content-Disposition: attachment + octet-stream
        // （见 ObjectStorage.Disposition / SysFileController#download）。
        // 本用例存在的意义是：别让下一个人以为「SVG 不在白名单」这句话拦住了 SVG 内容。
        Optional<FileTypeDetector.DetectedType> detected = FileTypeDetector.detect(write("x.txt", svg));
        assertThat(detected).isPresent();
        assertThat(detected.get().canonicalExt()).isEqualTo("txt");
    }

    // ====================================================================
    // 保留侧 —— 没有这一组，「detect 一律返回 empty」也会让上面全绿
    // ====================================================================

    @Test
    @DisplayName("【保留侧】正常文件判得对：xlsx / xls / zip / png / txt 各归各位")
    void realFilesKeepTheirOwnType() throws IOException {
        assertThat(FileTypeDetector.detect(write("a.xlsx", xlsx())).orElseThrow().canonicalExt())
                .isEqualTo("xlsx");
        assertThat(FileTypeDetector.detect(write("b.xls", xls())).orElseThrow().canonicalExt())
                .isEqualTo("xls");
        assertThat(FileTypeDetector.detect(write("c.zip", plainZip())).orElseThrow().canonicalExt())
                .as("不含 word//xl//ppt/ 前缀的 ZIP 就是普通 zip，不该被误判成 Office 文档")
                .isEqualTo("zip");
        assertThat(FileTypeDetector.detect(write("d.png", png())).orElseThrow().canonicalExt())
                .isEqualTo("png");
        assertThat(FileTypeDetector.detect(
                        write("e.txt", "李小明,S20260001,高一(3)班".getBytes(StandardCharsets.UTF_8)))
                        .orElseThrow().canonicalExt())
                .as("中文 UTF-8 文本必须通过 —— 学生名单导出就是这个形态")
                .isEqualTo("txt");
    }

    @Test
    @DisplayName("【保留侧】GBK 文本也要通过（Windows 记事本默认编码，误拒会被用户当成系统坏了）")
    void gbkTextIsAccepted() throws IOException {
        byte[] gbk = "李小明,高一3班".getBytes(java.nio.charset.Charset.forName("GBK"));

        assertThat(FileTypeDetector.detect(write("gbk.txt", gbk)).orElseThrow().canonicalExt())
                .isEqualTo("txt");
    }

    @Test
    @DisplayName("【保留侧】jpeg 归一到 jpg —— 强制重命名只能有一个写法")
    void jpegIsCanonicalisedToJpg() {
        assertThat(FileTypeDetector.canonical("JPEG")).contains("jpg");
        assertThat(FileTypeDetector.canonical(".jpg")).contains("jpg");
        assertThat(FileTypeDetector.canonical("svg")).isEmpty();
        assertThat(FileTypeDetector.canonical("exe")).isEmpty();
    }

    @Test
    @DisplayName("白名单恰好是 03-01 §7.1 列的 14 个扩展名（多一个少一个都红）")
    void whitelistMatchesTheSpec() {
        // 逐字来自 03-01 §7.1：jpg/jpeg/png/gif/webp/pdf/doc/docx/xls/xlsx/ppt/pptx/txt/zip
        for (String ext : new String[]{"jpg", "jpeg", "png", "gif", "webp", "pdf",
                "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip"}) {
            assertThat(FileTypeDetector.isWhitelisted(ext)).as(ext).isTrue();
        }
        // SVG 不在白名单内（00-通用约定 §7.4 逐字）
        for (String ext : new String[]{"svg", "html", "htm", "js", "exe", "sh", "jar", "mp4"}) {
            assertThat(FileTypeDetector.isWhitelisted(ext)).as(ext).isFalse();
        }
    }
}
