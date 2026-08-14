package com.edumatrix.auth.support;

import java.nio.charset.StandardCharsets;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.edumatrix.common.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 走真实 HTTP 链路的登录客户端（MockMvc）。
 *
 * <p><b>验证码走真流程</b>：先调 §1.1 拿 {@code captchaKey}，再从 Redis 读回原文填进登录请求。
 * 不给测试开后门（比如「测试环境跳过验证码」）—— 那样验证码这一环就永远没被验过，
 * 而它恰恰是判定顺序里的第②步。
 */
public class AuthTestClient {

    private final MockMvc mockMvc;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthTestClient(MockMvc mockMvc, StringRedisTemplate redisTemplate) {
        this.mockMvc = mockMvc;
        this.redisTemplate = redisTemplate;
    }

    /** 登录并返回整个响应体（{@code code} / {@code msg} / {@code data}）。 */
    public JsonNode login(String username, String password) throws Exception {
        // 清掉本 IP 的登录计数：一个用例里连登十几次是测试特有的形态，
        // 不清就会撞上 00-通用约定 §8 的「同一 IP 60 秒内最多 10 次」而全部 429
        redisTemplate.delete(RedisKeys.rateLimitLogin("127.0.0.1"));

        JsonNode captcha = json(mockMvc.perform(get("/api/v1/auth/captcha")).andReturn());
        String captchaKey = captcha.path("data").path("captchaKey").asText();
        String captchaCode = redisTemplate.opsForValue().get(captchaKey);

        String body = """
                {"username":"%s","password":"%s","captchaKey":"%s","captchaCode":"%s"}
                """.formatted(username, password, captchaKey, captchaCode);
        return json(mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn());
    }

    /** 登录并返回 accessToken；登录未成功时抛断言错误，让失败停在源头。 */
    public String loginForToken(String username, String password) throws Exception {
        JsonNode response = login(username, password);
        if (response.path("code").asInt() != 200) {
            throw new AssertionError("登录未成功：code=" + response.path("code").asInt()
                    + " msg=" + response.path("msg").asText());
        }
        return response.path("data").path("accessToken").asText();
    }

    public JsonNode getWithToken(String path, String accessToken) throws Exception {
        return json(mockMvc.perform(authorized(get(path), accessToken)).andReturn());
    }

    public MvcResult getRawWithToken(String path, String accessToken) throws Exception {
        return mockMvc.perform(authorized(get(path), accessToken)).andReturn();
    }

    public JsonNode postWithToken(String path, String accessToken, String body) throws Exception {
        return json(mockMvc.perform(authorized(post(path), accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : body)).andReturn());
    }

    public JsonNode putWithToken(String path, String accessToken, String body) throws Exception {
        return json(mockMvc.perform(authorized(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path), accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn());
    }

    public JsonNode refresh(String refreshToken) throws Exception {
        return json(mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}")).andReturn());
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder, String token) {
        // 00-通用约定 §2.1：Authorization: Bearer {accessToken}
        return builder.header("Authorization", "Bearer " + token);
    }

    private JsonNode json(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(content.isBlank() ? "{}" : content);
    }
}
