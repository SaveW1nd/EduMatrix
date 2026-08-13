package com.edumatrix.common.trace;

import java.util.function.Supplier;

/**
 * 无入站请求的执行入口（XXL-Job 任务、异步 Worker、消息消费）的 traceId 装配。
 *
 * <p>契约 §7.1 的原文：「异步任务从触发方继承 traceId，<b>继承不到则新生成并记录
 * {@code parentTraceId}</b>」。这里就是那一句的落点。
 *
 * <p><b>与 {@link TraceIdTaskDecorator} 的分工</b>：
 * <ul>
 *   <li>{@code TaskDecorator} 管的是「同一个 JVM 内、由某个请求线程<b>提交</b>到线程池」——
 *       此时能拿到提交方的 MDC，直接继承；
 *   <li>本类管的是「<b>没有提交方</b>」—— XXL-Job 调度器触发、从队列里取消息。
 *       此时继承不到，新生成一个，并把触发方的 traceId（若报文里带了）记为 parent。
 * </ul>
 *
 * <p><b>为什么 Job 里不直接复用调度批次的同一个 traceId</b>：一次调度可能扇出成成百上千个
 * 分片任务（契约 §2.8 规则 2 要求跨租户任务按租户分片）。全用同一个 traceId，
 * 按 traceId 检索会一次捞出整批日志，等于没有区分度；完全不记 parent 又断了「这批任务
 * 是哪一次调度触发的」这条线索。一个新 traceId + 一个 parentTraceId 两头都保住。
 *
 * <p>典型用法（Job 里与 {@code TenantHelper.runWithTenant} 叠着用）：
 * <pre>
 * TraceIdSupport.runWithNewTrace(schedulerTraceId, () -&gt;
 *     TenantHelper.runWithTenant(tenantId, () -&gt; settleService.settle(tenantId, date)));
 * </pre>
 */
public final class TraceIdSupport {

    private TraceIdSupport() {
    }

    /**
     * 新建一条链路执行，结束后复位。
     *
     * @param parentTraceId 触发方的 traceId，没有就传 {@code null}
     */
    public static void runWithNewTrace(String parentTraceId, Runnable action) {
        runWithNewTrace(parentTraceId, () -> {
            action.run();
            return null;
        });
    }

    /** 同 {@link #runWithNewTrace(String, Runnable)}，带返回值。 */
    public static <T> T runWithNewTrace(String parentTraceId, Supplier<T> action) {
        String previous = TraceIdHolder.get();
        TraceIdHolder.set(TraceIdHolder.generate());
        if (TraceIdHolder.isValid(parentTraceId)) {
            TraceIdHolder.setParent(parentTraceId);
        }
        try {
            return action.get();
        } finally {
            TraceIdHolder.clear();
            if (previous != null) {
                TraceIdHolder.set(previous);
            }
        }
    }

    /** 无触发方时的简写。 */
    public static void runWithNewTrace(Runnable action) {
        runWithNewTrace(null, action);
    }
}
