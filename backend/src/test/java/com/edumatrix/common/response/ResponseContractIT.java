package com.edumatrix.common.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.edumatrix.common.trace.TraceIdHolder;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.ProbeController;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 模块 01 完成判据第 2 条：<b>任一接口响应满足统一响应体 / ID 字符串化 / 东八区时间；
 * 响应头带 {@code X-Trace-Id}</b>（00-通用约定 §3 / §4 / §5 / §6，契约 §7.1）。
 *
 * <h2>两组测试，分工不同</h2>
 * <ul>
 *   <li><b>真实链路组</b>：走完整 Spring 上下文，验证 {@code TraceIdFilter} 与
 *       {@code GlobalExceptionHandler} 在真实过滤器链上生效 —— 包括「被 Sa-Token 拦下的请求
 *       同样带 traceId」这条（05-工程结构.md §G1：免登录接口出问题时，traceId 是唯一排查入口）；
 *   <li><b>序列化组</b>：用 {@code standaloneSetup} 挂上<b>容器里那个真实的 ObjectMapper</b>
 *       与真实的 {@code TraceIdFilter}，验证响应体形状。走 standalone 是因为探针端点
 *       不在免登录白名单里 —— 而<b>白名单只有四条、一条都不能为了测试而加</b>。
 * </ul>
 */
@IntegrationTest
class ResponseContractIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc standalone;

    @BeforeEach
    void setUp() {
        standalone = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new com.edumatrix.common.trace.TraceIdFilter())
                .build();
    }

    // ================================================================ 真实链路组

    @Test
    @DisplayName("响应头带 X-Trace-Id，且是 32 位 hex（契约 §7.1）")
    void traceIdHeaderIsAlwaysPresent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/anything"))
                .andExpect(header().exists(TraceIdHolder.HEADER_TRACE_ID))
                .andReturn();

        String traceId = result.getResponse().getHeader(TraceIdHolder.HEADER_TRACE_ID);
        assertThat(TraceIdHolder.isValid(traceId))
                .as("traceId 必须是 32 位 hex —— 用户截图报错时它是唯一的检索键")
                .isTrue();
    }

    @Test
    @DisplayName("网关已生成 traceId 时沿用，非法值丢弃重生成（防日志注入）")
    void incomingTraceIdIsReusedWhenValid() throws Exception {
        String given = "0123456789abcdef0123456789abcdef";
        mockMvc.perform(get("/api/v1/anything").header(TraceIdHolder.HEADER_TRACE_ID, given))
                .andExpect(header().string(TraceIdHolder.HEADER_TRACE_ID, given));

        MvcResult injected = mockMvc.perform(get("/api/v1/anything")
                        .header(TraceIdHolder.HEADER_TRACE_ID, "not-a-trace-id\n伪造的日志行"))
                .andReturn();
        String actual = injected.getResponse().getHeader(TraceIdHolder.HEADER_TRACE_ID);
        assertThat(actual)
                .as("X-Trace-Id 是客户端可控的头，原样进日志就是日志注入")
                .isNotEqualTo("not-a-trace-id\n伪造的日志行");
        assertThat(TraceIdHolder.isValid(actual)).isTrue();
    }

    @Test
    @DisplayName("未登录 → 框架层错误，HTTP 状态码与响应体 code 一致（§3.3）")
    void frameworkErrorKeepsHttpStatusAlignedWithCode() throws Exception {
        mockMvc.perform(get("/api/v1/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").exists())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(header().exists(TraceIdHolder.HEADER_TRACE_ID));
    }

    // ================================================================ 序列化组

    @Test
    @DisplayName("统一响应体 {code, msg, data}，且 bigint ID 一律是字符串（§3 / §5）")
    void unifiedEnvelopeAndStringifiedIds() throws Exception {
        standalone.perform(get(ProbeController.PATH_OBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("操作成功"))
                // 19 位雪花 ID 超过 JS 安全整数上限，传数字会静默丢精度
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.id").value("1953827104412590081"))
                // 可空 ID 输出为 null 而不是省略键：前端才能区分"没这个字段"与"这个字段为空"
                .andExpect(jsonPath("$.data.parentId").value((Object) null))
                .andExpect(header().exists(TraceIdHolder.HEADER_TRACE_ID));
    }

    @Test
    @DisplayName("时间格式与东八区：yyyy-MM-dd HH:mm:ss / yyyy-MM-dd，时长是整数秒（§6）")
    void dateTimeFormatAndDuration() throws Exception {
        standalone.perform(get(ProbeController.PATH_OBJECT))
                .andExpect(jsonPath("$.data.createTime").value("2026-08-12 10:30:00"))
                .andExpect(jsonPath("$.data.statDate").value("2026-08-12"))
                // 接口不接受也不返回时间戳与 ISO8601 带时区格式
                .andExpect(jsonPath("$.data.createTime").isString())
                .andExpect(jsonPath("$.data.duration").isNumber())
                .andExpect(jsonPath("$.data.duration").value(600));
    }

    @Test
    @DisplayName("分页 data 固定 {total, list}，total 是数字，无第三个同级字段（§4.2）")
    void pageResultShape() throws Exception {
        MvcResult result = standalone.perform(get(ProbeController.PATH_PAGE))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.total").value(138))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.list[0].id").isString())
                // summary 是可选的，不带聚合信息时这个键根本不出现，
                // 而不是出现一个 "summary": null
                .andExpect(jsonPath("$.data.summary").doesNotHaveJsonPath())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("data").size())
                .as("除 total / list / summary 外不得有其他同级字段（§4.2）")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("pageSize 上限 100 强制生效（§4.1：超出按 100 处理，不报 400）")
    void pageSizeIsCapped() {
        assertThat(PageResult.normalizePageSize(100000)).isEqualTo(100);
        assertThat(PageResult.normalizePageSize(null)).isEqualTo(10);
        assertThat(PageResult.normalizePageSize(0)).isEqualTo(10);
        assertThat(PageResult.normalizePageSize(50)).isEqualTo(50);
        assertThat(PageResult.normalizePageNum(null)).isEqualTo(1);
        assertThat(PageResult.normalizePageNum(-3)).isEqualTo(1);
    }
}
