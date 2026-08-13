package com.edumatrix.common.trace;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 让异步线程池继承提交方的 MDC（契约 §7.1：traceId 经 MDC 贯穿<b>异步线程池</b>与 XXL-Job）。
 *
 * <p>不加它，{@code @Async} 与任何 {@code ThreadPoolTaskExecutor} 上的日志都不带 traceId ——
 * <b>链路断在异步边界</b>，而异步边界恰好是出问题最难复现的地方。
 *
 * <p><b>两个细节，缺一个就会串味</b>：
 * <ol>
 *   <li>快照要在<b>提交时</b>取（构造 {@code decorate} 返回的 Runnable 那一刻仍在提交线程上），
 *       不能在 {@code run()} 里取 —— 那时已经在工作线程上了；
 *   <li>{@code finally} 里必须<b>恢复工作线程原有的 MDC</b>而不是清空。线程池会复用线程，
 *       直接 {@code clear()} 会把该线程上别的上下文一并抹掉。
 * </ol>
 *
 * <p>{@code TraceIdFilter} 已保证 Web 请求线程一定有 traceId；提交方没有 traceId 的场景
 * （如启动期任务）走 {@link TraceIdSupport#runWithNewTrace} 那条「新生成 + 记 parentTraceId」的路。
 */
public class TraceIdTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 在提交线程上取快照
        Map<String, String> submitterContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> workerOriginal = MDC.getCopyOfContextMap();
            try {
                if (submitterContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(submitterContext);
                }
                runnable.run();
            } finally {
                // 恢复而非清空：线程池复用，别把这条线程上原有的上下文抹掉
                if (workerOriginal == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(workerOriginal);
                }
            }
        };
    }
}
