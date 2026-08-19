package com.edumatrix.integration.aliyun;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.edumatrix.common.file.FileConstants;
import com.edumatrix.common.file.ObjectStorage;

/**
 * <b>T-F</b>：签名地址上必须带 {@code response-content-disposition} 与
 * {@code response-content-type} —— 这是 D-4 定案在 302 路径上<b>唯一</b>的抓手。
 *
 * <h2>为什么这条测试必须存在</h2>
 * <p>{@code 00-通用约定} §7.4 要求「下载统一 {@code Content-Disposition: attachment}
 * + {@code X-Content-Type-Options: nosniff}」。而 03-01 §7.3 的 OSS 路径是
 * <b>302 重定向</b>，重定向之后浏览器请求的是 OSS —— 我们在 {@code SysFileController}
 * 里设的响应头<b>一个都不生效</b>，且 {@code sys_file.storage} 的 DDL 默认值就是 2。
 * 也就是说：<b>这条基线在生产上从来不生效</b>，属本项目已出现四次的
 * 「以为存在、实际从未生效的保障」那一族。
 *
 * <p>处置是把两个 {@code response-*} 参数附在签名地址上（它们<b>参与签名</b>，
 * 客户端改一个字签名就失效）。而「有没有真的附上」这件事，
 * 在没有真实 OSS 的 CI 里<b>只有这条测试能验</b>——
 * {@code generatePresignedUrl} 是纯本地签名、不发任何网络请求，所以可以离线跑。
 *
 * <h2>它会不会红</h2>
 * <p>把 {@code OssClient#presignRequest} 里的 {@code setResponseHeaders(...)} 删掉
 * → 两条断言立刻红。把 {@code ATTACHMENT} 档的 {@code inline}/{@code attachment} 写反
 * → {@link #attachmentDispositionForcesDownload} 红。
 */
class OssPresignParamsTest {

    private static final String BUCKET = "edumatrix-test";
    private static final String KEY = "import_excel/2026/08/19/1953827104412590090.xlsx";
    private static final String FILE_NAME = "学生名单-高一3班.xlsx";

    /**
     * 一次性客户端，<b>只用于本地签名</b>。
     *
     * <p>刻意不构造 {@code OssClient}：它的构造函数会做启动自检（{@code getBucketInfo}），
     * 那是要连网的。被测的是「签名参数组装」这一段，故走 {@code presignRequest} 这个
     * package-private 的 static 入口 —— 拆出它就是为了这条测试。
     */
    private static String sign(GeneratePresignedUrlRequest request) {
        OSS oss = new OSSClientBuilder()
                .build("https://oss-cn-hangzhou.aliyuncs.com", "test-ak", "test-sk");
        try {
            return oss.generatePresignedUrl(request).toString();
        } finally {
            oss.shutdown();
        }
    }

    @Test
    @DisplayName("T-F 下载档：签名地址带 response-content-disposition=attachment 与 octet-stream")
    void attachmentDispositionForcesDownload() {
        GeneratePresignedUrlRequest request = OssClient.presignRequest(
                BUCKET, KEY, FILE_NAME, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ObjectStorage.Disposition.ATTACHMENT, FileConstants.SIGNED_URL_TTL);

        assertThat(request.getResponseHeaders()).isNotNull();
        assertThat(request.getResponseHeaders().getContentDisposition()).startsWith("attachment;");
        assertThat(request.getResponseHeaders().getContentType())
                .as("nosniff 在 302 路径上拿不到，octet-stream + attachment 是它缺席时的主力")
                .isEqualTo("application/octet-stream");

        String url = URLDecoder.decode(sign(request), StandardCharsets.UTF_8);
        assertThat(url)
                .as("参数没落进地址的话，302 之后浏览器拿到的就是 OSS 的默认响应头")
                .contains("response-content-disposition=attachment");
        assertThat(url).contains("response-content-type=application/octet-stream");
        assertThat(url).as("两个 override 参数参与签名，故地址上必然有签名").contains("Signature=");
    }

    @Test
    @DisplayName("D-2 内联档：course_cover / material_image / avatar 用 inline + 真实 MIME")
    void inlineDispositionKeepsRealMimeForImgTag() {
        GeneratePresignedUrlRequest request = OssClient.presignRequest(
                BUCKET, "course_cover/2026/08/19/1953827104412590091.png", "封面.png", "image/png",
                ObjectStorage.Disposition.INLINE, FileConstants.SIGNED_URL_TTL);

        assertThat(request.getResponseHeaders().getContentDisposition()).startsWith("inline;");
        assertThat(request.getResponseHeaders().getContentType())
                .as("给 octet-stream 的话 <img> 渲染不出来，而 D-2 选内联档正是因为 <img> 带不了 Bearer 头")
                .isEqualTo("image/png");

        String url = URLDecoder.decode(sign(request), StandardCharsets.UTF_8);
        assertThat(url).contains("response-content-disposition=inline");
        assertThat(url).contains("response-content-type=image/png");
    }

    @Test
    @DisplayName("中文文件名按 RFC 5987 编码，与 03-01 §7.3 的响应头示例同格式")
    void chineseFileNameIsRfc5987Encoded() {
        GeneratePresignedUrlRequest request = OssClient.presignRequest(
                BUCKET, KEY, FILE_NAME, "application/octet-stream",
                ObjectStorage.Disposition.ATTACHMENT, FileConstants.SIGNED_URL_TTL);

        String disposition = request.getResponseHeaders().getContentDisposition();
        assertThat(disposition).contains("filename*=UTF-8''");
        assertThat(disposition).as("裸中文会让部分客户端把文件名截断或乱码").doesNotContain("学生名单");
        // 空格编成 %20 而不是 +：filename* 走的是 RFC 5987 的 percent-encoding，不是表单编码
        assertThat(disposition).doesNotContain("+");
    }

    @Test
    @DisplayName("endpoint 归一化：带不带 https:// 与结尾斜杠都视为同一个主机")
    void endpointHostNormalisation() {
        String canonical = "oss-cn-hangzhou-internal.aliyuncs.com";
        assertThat(OssClient.hostOf(canonical)).isEqualTo(canonical);
        assertThat(OssClient.hostOf("https://" + canonical)).isEqualTo(canonical);
        assertThat(OssClient.hostOf("http://" + canonical + "/")).isEqualTo(canonical);
        assertThat(OssClient.hostOf("  HTTPS://OSS-CN-HANGZHOU-INTERNAL.ALIYUNCS.COM  "))
                .isEqualTo(canonical);
        assertThat(OssClient.hostOf(null)).isEmpty();

        // 跨地域必须比出不同 —— 这正是启动自检要抓的那件事：
        // oss-cn-beijing 也以 oss-cn- 开头，靠前缀判是判不出来的
        assertThat(OssClient.hostOf("oss-cn-beijing-internal.aliyuncs.com"))
                .as("桶在北京、ECS 在杭州：走公网、计流量费、延迟高，而且完全静默")
                .isNotEqualTo(OssClient.hostOf(canonical));
        // "把公网 endpoint 填进内网那一格"同样比得出不同
        assertThat(OssClient.hostOf("oss-cn-hangzhou.aliyuncs.com")).isNotEqualTo(canonical);
    }

    @Test
    @DisplayName("有效期恰好是 30 分钟（00-通用约定 §7.4 / 03-01 §7.3 / 03-05 §4.8 三处同一个数字）")
    void ttlIsThirtyMinutes() {
        assertThat(FileConstants.SIGNED_URL_TTL).isEqualTo(Duration.ofMinutes(30));

        GeneratePresignedUrlRequest request = OssClient.presignRequest(
                BUCKET, KEY, FILE_NAME, "application/octet-stream",
                ObjectStorage.Disposition.ATTACHMENT, FileConstants.SIGNED_URL_TTL);

        long deltaMillis = request.getExpiration().getTime() - System.currentTimeMillis();
        assertThat(deltaMillis).isBetween(Duration.ofMinutes(29).toMillis(), Duration.ofMinutes(30).toMillis());
    }
}
