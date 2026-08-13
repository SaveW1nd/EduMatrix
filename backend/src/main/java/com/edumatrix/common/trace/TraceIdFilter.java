package com.edumatrix.common.trace;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * traceId 过滤器：装在<b>最外层</b>（契约 §7.1 / 05-工程结构.md §G1 的链路顺序图）。
 *
 * <pre>
 * ① TraceIdFilter（Servlet Filter，最外层）
 *       ↓  免登录接口同样要有 traceId —— 密钥接口出问题时，没有它就无从定位
 * ② Sa-Token 拦截器（SaInterceptor），排除四条免登录路径
 *       ↓
 * ③ Controller
 * </pre>
 *
 * <p><b>为什么必须在 Sa-Token 之前</b>：四条免登录接口里的
 * {@code GET /api/v1/vod/decrypt-key} 由 hls.js 内核发起，不带任何自定义请求头、
 * 不经过前端的 axios 封装。它一旦取不到密钥，前端只能看到一个播放器错误事件 ——
 * 服务端日志里的 traceId 是唯一的排查入口。若 traceId 装在 Sa-Token 之后，
 * 被拦截器拒掉的请求就没有 traceId，恰好是最需要排查的那些。
 *
 * <p><b>响应头一定要设，而且要在 {@code doFilter} 之前设</b>：响应一旦开始写出，
 * 就不能再加 header 了 —— 异常路径、大响应体流式写出都会踩到这个。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(TraceIdHolder.HEADER_TRACE_ID);
        // 网关已生成时沿用，便于跨服务串链；非法值一律丢弃重新生成 ——
        // 这个头是客户端可控的，原样进日志就是日志注入
        String traceId = TraceIdHolder.isValid(incoming) ? incoming : TraceIdHolder.generate();

        TraceIdHolder.set(traceId);
        // 必须在写出响应体之前设置：响应一旦提交就加不上 header 了
        response.setHeader(TraceIdHolder.HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat 线程会被复用，不清就是下一个请求带着上一个请求的 traceId
            TraceIdHolder.clear();
        }
    }
}
