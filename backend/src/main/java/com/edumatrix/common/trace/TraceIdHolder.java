package com.edumatrix.common.trace;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * traceId 的持有与传递（契约 §7.1 可观测性）。
 *
 * <p>每个入站请求生成一个 32 位 hex 的 {@code traceId}，经 MDC 贯穿日志、异步线程池、
 * XXL-Job 与 MQ，并<b>原样回传响应头 {@code X-Trace-Id}</b> —— 出问题时用户截图即可定位。
 * 两个前端工程都要从响应头取出它并在报错提示中展示（05-工程结构.md §A3 末表）。
 *
 * <p><b>异步任务继承不到时新生成，并记录 {@code parentTraceId}</b>（契约 §7.1 原文）。
 * 记 parent 而不是直接复用，是因为一次调度可能扇出成很多个任务：全用同一个 traceId
 * 会让日志检索一次捞出成千上万条无关记录，而完全不记又断了链路。
 *
 * <p><b>免登录接口同样要有 traceId</b>（05-工程结构.md §G1）：{@code GET /api/v1/vod/decrypt-key}
 * 出问题时，没有它就无从定位 —— 那条链路上既没有登录用户也不经过前端的 axios 实例。
 * 所以 {@link TraceIdFilter} 装在<b>最外层</b>，在 Sa-Token 拦截器之前。
 */
public final class TraceIdHolder {

    /** MDC 键名。logback-spring.xml 的 pattern 里引用的就是它。 */
    public static final String MDC_TRACE_ID = "traceId";

    /** 异步任务继承不到 traceId 时，记录触发方的 traceId。 */
    public static final String MDC_PARENT_TRACE_ID = "parentTraceId";

    /** 响应头名。原样回传，前端据此展示（契约 §7.1）。 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    private TraceIdHolder() {
    }

    /** 生成一个 32 位 hex traceId。 */
    public static String generate() {
        UUID uuid = UUID.randomUUID();
        return digits(uuid.getMostSignificantBits()) + digits(uuid.getLeastSignificantBits());
    }

    private static String digits(long value) {
        return String.format("%016x", value);
    }

    /** 当前 traceId；不在任何链路中时返回 {@code null}。 */
    public static String get() {
        return MDC.get(MDC_TRACE_ID);
    }

    public static void set(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            MDC.remove(MDC_TRACE_ID);
        } else {
            MDC.put(MDC_TRACE_ID, traceId);
        }
    }

    public static void setParent(String parentTraceId) {
        if (parentTraceId == null || parentTraceId.isEmpty()) {
            MDC.remove(MDC_PARENT_TRACE_ID);
        } else {
            MDC.put(MDC_PARENT_TRACE_ID, parentTraceId);
        }
    }

    /** 只清 traceId 相关的键，不动 MDC 里别人放的东西。 */
    public static void clear() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_PARENT_TRACE_ID);
    }

    /**
     * 校验外部传入的 traceId 是否是合法的 32 位 hex。
     *
     * <p><b>为什么要校验</b>：{@code X-Trace-Id} 是客户端可控的请求头，会原样进日志。
     * 不校验就等于把任意字符串（换行、控制字符）写进日志行 —— 那是日志注入，
     * 能伪造出看起来像另一条真实日志的记录。
     */
    public static boolean isValid(String traceId) {
        if (traceId == null || traceId.length() != 32) {
            return false;
        }
        for (int i = 0; i < 32; i++) {
            char c = traceId.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
