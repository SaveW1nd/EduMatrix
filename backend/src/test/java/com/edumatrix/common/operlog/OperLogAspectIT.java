package com.edumatrix.common.operlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.edumatrix.common.idempotent.IdempotentAspect;
import com.edumatrix.common.response.GlobalExceptionHandler;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.OperLogProbeController;
import com.edumatrix.support.TestCurrentContextProvider;
import com.edumatrix.support.mapper.ProbeOperLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code @OperLog} 切面 —— <b>F-25 的验收</b>。
 *
 * <p>F-25 逐字列了切面必须承担的四件事：「{@code params} 脱敏白/黑名单
 * （{@code guardian_phone} / {@code phone}，契约 §7.2）、{@code cost_ms}、
 * {@code status} / {@code error_msg}、以及 §8.2 的查询接口」。前三件在本类，
 * 第四件在 {@code system/log} 的 IT。
 *
 * <p><b>每条用例都能说清"被测机制没实现时它会不会红"</b>：
 * 切面整个删掉 → 全部红（一行都写不出来）；只删脱敏 → {@link #successWritesMaskedParams} 红；
 * 只把 {@code catch} 去掉 → {@link #bizExceptionWritesFailureRow} 红；
 * 把 {@code @Order} 删掉 → {@link #aspectSitsOutsideTransactionAndInsideIdempotent} 红。
 */
@IntegrationTest
class OperLogAspectIT {

    private static final long TENANT_A = 1953827104412590001L;
    private static final long OPERATOR_ID = 1950000000000000101L;
    private static final long NODE_ID = 1953827104412590401L;

    /**
     * <b>standalone，但挂的是容器里那个 Bean</b>。
     *
     * <p>两条约束同时成立才有这个写法：
     * <ul>
     *   <li>探针端点不在免登录白名单里，而 {@code 00-通用约定} §2.3 的白名单<b>只有四条、
     *       一条都不能为了测试而加</b>（{@code ResponseContractIT} 已确立此先例）；</li>
     *   <li>但 {@code new OperLogProbeController()} 是<b>裸对象、没有 AOP 代理</b>，
     *       切面根本不会被触发 —— 那样这组用例会「全绿而什么都没验」。</li>
     * </ul>
     * <p>所以从容器取 Bean（它<b>就是</b>那个 AOP 代理）再挂到 standalone 上：
     * 绕过的只有 Sa-Token 拦截器，切面链路是真的。
     */
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @Autowired
    private ProbeOperLogMapper probeOperLogMapper;

    @Autowired
    private TestCurrentContextProvider contextProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void useTenantSession() {
        contextProvider.asTenantUser(TENANT_A, OPERATOR_ID, NODE_ID);
        mockMvc = MockMvcBuilders
                .standaloneSetup(applicationContext.getBean(OperLogProbeController.class))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(globalExceptionHandler)
                .build();
    }

    private String body(String realName, String initPassword, String guardianPhone) throws Exception {
        return objectMapper.writeValueAsString(
                new OperLogProbeController.ProbeReq(realName, initPassword, guardianPhone));
    }

    // ====================================================================
    // ① params 脱敏（F-25 第 1 件）
    // ====================================================================

    @Test
    @DisplayName("成功写一行 status=0，且 params 里口令整值替换、监护人手机号掩码、普通字段原样保留")
    void successWritesMaskedParams() throws Exception {
        mockMvc.perform(post(OperLogProbeController.PATH_OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("李小明", "Init5678!", "13912344001")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(200));

        Map<String, Object> row = probeOperLogMapper.selectLatestByAction(OperLogProbeController.ACTION_OK);

        assertThat(row).as("切面没写行 —— @OperLog 又变回了一个不产生任何行为的注解").isNotNull();
        assertThat(row.get("module")).isEqualTo(OperLogProbeController.MODULE);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(OperLogWriter.STATUS_SUCCESS);
        assertThat(row.get("errorMsg")).isNull();

        String params = (String) row.get("params");
        assertThat(params).as("saveParams 默认为 true，params 不该为空").isNotNull();
        // 攻击侧：明文一个字都不能在
        assertThat(params).doesNotContain("Init5678!");
        assertThat(params).doesNotContain("13912344001");
        assertThat(params).contains(SensitiveParamMasker.REDACTED);
        assertThat(params).contains("139****4001");
        // 保留侧：不加这一条，把 params 整列写死成 "***" 也会全绿
        assertThat(params).as("普通字段被脱掉的话 params 这一列就没有审计价值了").contains("李小明");

        // method 是「HTTP 方法 + 路径」（03-01 §8.2 响应字段说明逐字）
        assertThat((String) row.get("method")).isEqualTo("POST " + OperLogProbeController.PATH_OK);
        // 租户落在会话租户上（超管另有一档，见 OperLogWriter 的四档表）
        assertThat(((Number) row.get("tenantId")).longValue()).isEqualTo(TENANT_A);
        assertThat(((Number) row.get("userId")).longValue()).isEqualTo(OPERATOR_ID);
    }

    // ====================================================================
    // ② cost_ms（F-25 第 2 件）
    // ====================================================================

    @Test
    @DisplayName("cost_ms 被写入且非负（DDL 是 NOT NULL DEFAULT 0，写不进去看不出来）")
    void costMsIsRecorded() throws Exception {
        mockMvc.perform(post(OperLogProbeController.PATH_OK)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("王小红", "x", "13800000000")));

        Map<String, Object> row = probeOperLogMapper.selectLatestByAction(OperLogProbeController.ACTION_OK);
        assertThat(((Number) row.get("costMs")).intValue()).isGreaterThanOrEqualTo(0);
    }

    // ====================================================================
    // ③ status / error_msg（F-25 第 3 件）
    // ====================================================================

    @Test
    @DisplayName("业务码拒绝也要记：status=1、error_msg 带 code，且异常原样重抛不改变业务结果")
    void bizExceptionWritesFailureRow() throws Exception {
        mockMvc.perform(post(OperLogProbeController.PATH_FAIL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("李小明", "Init5678!", "13912344001")))
                // 切面不得吞异常：业务码必须原样到达客户端
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(10011));

        Map<String, Object> row = probeOperLogMapper.selectLatestByAction(OperLogProbeController.ACTION_FAIL);

        assertThat(row).as("只记成功不记失败的话，排查越权时正好缺掉关键那部分（契约 §7.1）").isNotNull();
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(OperLogWriter.STATUS_FAIL);
        assertThat((String) row.get("errorMsg")).contains("code=10011");
        // 失败行同样要脱敏 —— 失败路径漏脱是最容易漏的那一处
        assertThat((String) row.get("params")).doesNotContain("Init5678!");
    }

    // ====================================================================
    // saveParams = false
    // ====================================================================

    @Test
    @DisplayName("saveParams=false 时 params 为 null（§3.6 重置密码靠它，请求体就是新密码明文）")
    void saveParamsFalseWritesNullParams() throws Exception {
        mockMvc.perform(post(OperLogProbeController.PATH_NO_PARAMS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("李小明", "Init5678!", "13912344001")));

        Map<String, Object> row =
                probeOperLogMapper.selectLatestByAction(OperLogProbeController.ACTION_NO_PARAMS);

        assertThat(row).isNotNull();
        assertThat(row.get("params")).isNull();
        // 但这一行本身必须在 —— "不记参数" ≠ "不记这次操作"
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(OperLogWriter.STATUS_SUCCESS);
    }

    // ====================================================================
    // 切面顺序
    // ====================================================================

    @Test
    @DisplayName("@Order 在场且落在「事务外层、幂等内层」这个区间（删掉注解要红）")
    void aspectSitsOutsideTransactionAndInsideIdempotent() {
        Order order = AnnotationUtils.findAnnotation(OperLogAspect.class, Order.class);

        assertThat(order)
                .as("没有 @Order 就等于把「失败记录会不会被事务回滚掉」交给运气")
                .isNotNull();
        assertThat(order.value()).isEqualTo(OperLogAspect.ORDER);
        // 比事务小 → 在事务外层：业务回滚时那条 status=1 的记录必须留下来
        assertThat(OperLogAspect.ORDER)
                .as("排到事务内层的话，失败记录会跟着业务事务一起回滚，而那正是审计最需要的部分")
                .isLessThan(Ordered.LOWEST_PRECEDENCE);
        // 比幂等大 → 在幂等内层：X-Request-Id 命中重放时业务没执行，不该再记一条"新操作"
        assertThat(OperLogAspect.ORDER)
                .as("排到幂等外层的话，一次重放会被记成第二次操作")
                .isGreaterThan(IdempotentAspect.ORDER);
    }
}
