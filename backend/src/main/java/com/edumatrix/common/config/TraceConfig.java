package com.edumatrix.common.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.edumatrix.common.trace.TraceIdFilter;
import com.edumatrix.common.trace.TraceIdTaskDecorator;

/**
 * 链路追踪装配（契约 §7.1）。
 *
 * <p>{@link TraceIdFilter} 注册在<b>最高优先级</b>，即整条链路的最外层 ——
 * 排在 Sa-Token 拦截器之前（05-工程结构.md §G1 的链路顺序图）。
 * <b>免登录接口同样要有 traceId</b>：{@code GET /api/v1/vod/decrypt-key} 出问题时，
 * 它由 hls.js 内核发起、不经过前端 axios 封装，服务端日志是唯一排查入口。
 *
 * <p>异步线程池挂 {@link TraceIdTaskDecorator}，否则 traceId <b>断在异步边界</b>，
 * 而异步边界恰好是最难复现的地方。
 */
@Configuration
@EnableAsync
public class TraceConfig {

    /** 异步线程池的 Bean 名。{@code @Async("edumatrixTaskExecutor")} 显式指定它。 */
    public static final String ASYNC_EXECUTOR = "edumatrixTaskExecutor";

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }

    /**
     * 通用异步线程池。
     *
     * <p><b>拒绝策略用 {@code CallerRunsPolicy}</b>：队列满时让提交方自己跑，
     * 从而把压力反压回上游。用 {@code AbortPolicy} 会直接丢任务 ——
     * 而本项目的异步任务里有导入导出、心跳落盘这类"丢了没人知道"的活。
     *
     * <p><b>无会话入口仍必须自己包 {@code TenantHelper.runWithTenant}</b>：
     * 本装饰器传的是 MDC（traceId），<b>不传租户上下文</b>。两者故意分开 ——
     * 租户上下文必须由数据显式携带，不得依赖线程残留（契约 §2.8 规则 1）。
     */
    @Bean(name = ASYNC_EXECUTOR)
    public Executor edumatrixTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(256);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("edumatrix-async-");
        executor.setTaskDecorator(new TraceIdTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
