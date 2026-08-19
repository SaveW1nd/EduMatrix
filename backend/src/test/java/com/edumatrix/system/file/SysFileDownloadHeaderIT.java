package com.edumatrix.system.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.edumatrix.common.file.FileBizType;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestCurrentContextProvider;
import com.edumatrix.system.file.controller.SysFileController;
import com.edumatrix.system.file.entity.SysFile;
import com.edumatrix.system.file.service.FileService;

/**
 * <b>T-E</b>：{@code storage=1} 本地路径的下载响应头
 * （{@code 00-通用约定} §7.4：{@code Content-Disposition: attachment}
 * + {@code X-Content-Type-Options: nosniff}）。
 *
 * <h2>⚠ 这条测试<b>覆盖不到生产</b>，必须写清楚</h2>
 * <p>{@code sys_file.storage} 的 DDL 默认值是 <b>2</b>，生产一律走 OSS，
 * 而 OSS 路径是 <b>302 重定向</b> —— 302 之后浏览器请求的是 OSS，
 * 本类断言的这三个头<b>一个都不生效</b>。这正是 D-4 那条「§7.4 的下载头基线
 * 在生产上从来不生效」的发现。
 *
 * <p>生产路径的等价保障在
 * {@code com.edumatrix.integration.aliyun.OssPresignParamsTest}（T-F）：
 * 断言签名地址上带着参与签名的
 * {@code response-content-disposition=attachment} 与
 * {@code response-content-type=application/octet-stream}。
 * <b>两条测试合起来才覆盖两条路径</b>，任缺一条就会出现「以为测过了」。
 *
 * <p>用 standalone 挂容器里的 Controller Bean：三个文件接口不在
 * {@code 00-通用约定} §2.3 的四条免登录白名单里，而那份白名单
 * <b>一条都不能为了测试而加</b>（{@code ResponseContractIT} 先例）。
 */
@IntegrationTest
class SysFileDownloadHeaderIT {

    private static final long TENANT_A = 1953827104412590001L;
    private static final long ADMIN_USER_ID = 1953827104412590102L;
    private static final long ADMIN_NODE_ID = 1953827104412590001L;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FileService fileService;

    @Autowired
    private TestCurrentContextProvider contextProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        contextProvider.asTenantUser(TENANT_A, ADMIN_USER_ID, ADMIN_NODE_ID);
        mockMvc = MockMvcBuilders
                .standaloneSetup(applicationContext.getBean(SysFileController.class))
                .build();
    }

    @Test
    @DisplayName("T-E storage=1：attachment + nosniff + octet-stream 三个头齐（删任一即红）")
    void localDownloadCarriesAttachmentAndNosniff() throws Exception {
        SysFile saved = fileService.upload(
                new MockMultipartFile("file", "学生名单-高一3班.xlsx", null, xlsxBytes()),
                FileBizType.COMMON.code());

        MvcResult result = mockMvc.perform(get("/api/v1/system/files/" + saved.getId() + "/download"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).as("§7.4：下载统一 Content-Disposition: attachment").startsWith("attachment;");
        // 中文名按 RFC 5987 编码，与 03-01 §7.3 的响应头示例同格式
        assertThat(disposition).contains("filename*=UTF-8''");
        assertThat(disposition).doesNotContain("学生名单");

        assertThat(result.getResponse().getHeader("X-Content-Type-Options"))
                .as("§7.4：X-Content-Type-Options: nosniff。"
                        + "⚠ 本条只覆盖 storage=1；生产 storage=2 走 302，此头拿不到（D-4）")
                .isEqualTo("nosniff");

        assertThat(result.getResponse().getContentType()).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("下载不通过归属校验时返回 404 且响应体不带任何地址（不暴露存在性）")
    void deniedDownloadIsNotFoundWithoutLeakingAnything() throws Exception {
        // import_excel 需要 checker（模块 17 未落地）→ fail-closed
        SysFile saved = fileService.upload(
                new MockMultipartFile("file", "名单.xlsx", null, xlsxBytes()),
                FileBizType.IMPORT_EXCEL.code());

        MvcResult result = mockMvc.perform(get("/api/v1/system/files/" + saved.getId() + "/download"))
                .andReturn();

        // standalone 没挂全局异常处理器，BizException 直接冒出来即可证明"没有返回内容"；
        // 业务码到 HTTP 的映射由 ResponseContractIT 覆盖，这里只确认没有任何地址泄露
        assertThat(result.getResolvedException()).isNotNull();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .as("被拒的下载绝不能带 Location —— 那等于把签名地址送出去了")
                .isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(saved.getFileUrl());
    }

    private static byte[] xlsxBytes() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("s").createRow(0).createCell(0).setCellValue("x");
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
