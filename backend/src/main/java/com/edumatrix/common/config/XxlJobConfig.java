package com.edumatrix.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;

/**
 * XXL-Job <b>执行器</b>装配 —— 补上 {@code @XxlJob} 注解此前缺的那一半。
 *
 * <h2>⚠ 在本类之前，{@code xxl.job.*} 那五个配置项<b>没有任何东西在读</b></h2>
 * <p>{@code application.yml} 从模块 01 起就有 {@code xxl.job.enabled} /
 * {@code admin-addresses} / {@code access-token} / {@code executor.*}，
 * {@code deploy/.env.example} 也有 {@code XXL_JOB_ENABLED=false}，
 * 读起来像是「置 true 即可启用」。而全库<b>没有 {@code XxlJobSpringExecutor} 这个 Bean</b> ——
 * 置 true 也不会有任何事发生，两个 {@code @XxlJob} 注解
 * （{@code anonymizeArchivedStudent} / {@code tempFileCleanup}）是<b>惰性的</b>。
 *
 * <p>这与 {@code @OperLog} 在模块 05 之前的状态是同一个形态，也是本项目
 * 「以为存在、实际从未生效的保障」的<b>第六例</b>（前五：基线 charset 子句、
 * {@code @AssertTrue} 拦不住不传、{@code useSSL=true} 而服务端没开 TLS、
 * §7.4 下载头在 302 路径不生效、契约 §7.2 第 5 条日志保留期零承载）。
 *
 * <h2>{@code enabled=false} 时整个 Bean 不装配，而不是装配一个空壳</h2>
 * <p>{@code @ConditionalOnProperty} 让开发与集成测试环境<b>压根不创建执行器</b> ——
 * 创建了它会去连调度中心、连不上就每几秒刷一条 ERROR，把测试日志淹掉，
 * 最后没人看日志。默认 {@code false}（{@code matchIfMissing = false}）。
 *
 * <h2>启动日志把「注册到哪」打出来</h2>
 * <p>执行器连不上调度中心时<b>不会让应用启动失败</b>（这是 XXL-Job 的设计，也是对的 ——
 * 调度中心挂了不该把业务服务一起拖下水）。代价是「配错了地址」这件事
 * 只在调度中心侧表现为「执行器列表是空的」，而应用侧一切正常。
 * 所以这里把 admin 地址与执行器端口打进启动日志，
 * 与 {@code OssClient} 那行「对象存储 = …」同一个用途：<b>给一条可 grep 的、
 * 能证明"它到底接到哪儿了"的事实</b>。
 *
 * <h2>日志目录跟随 {@code LOG_PATH}</h2>
 * <p>{@code edumatrix.service} 里 {@code LOG_PATH=/var/log/edumatrix}，
 * 且 {@code ReadWritePaths} 只放开了这一个目录（{@code ProtectSystem=strict}）。
 * 执行器日志写在它的子目录下，<b>不能另选路径</b> —— 选了会因只读文件系统失败，
 * 而 XXL-Job 会把这个失败吞成一条 WARN。
 */
@Configuration
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "true")
public class XxlJobConfig {

    private static final Logger log = LoggerFactory.getLogger(XxlJobConfig.class);

    @Bean(initMethod = "start", destroyMethod = "destroy")
    public XxlJobSpringExecutor xxlJobSpringExecutor(
            @Value("${xxl.job.admin-addresses:}") String adminAddresses,
            @Value("${xxl.job.access-token:}") String accessToken,
            @Value("${xxl.job.executor.appname}") String appName,
            @Value("${xxl.job.executor.port:9999}") int port,
            @Value("${xxl.job.executor.logretentiondays:30}") int logRetentionDays,
            @Value("${LOG_PATH:logs}") String logPath) {

        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appName);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath + "/xxl-job");
        executor.setLogRetentionDays(logRetentionDays);

        if (adminAddresses == null || adminAddresses.isBlank()) {
            // 启用了执行器却没给调度中心地址：任务永远不会被触发，而应用一切正常。
            // 这条必须是 WARN 且说清后果 —— 它是「启用了但没接上」唯一的痕迹
            log.warn("XXL-Job 执行器已启用，但 xxl.job.admin-addresses 为空 —— "
                    + "执行器不会注册到任何调度中心，两个任务（anonymizeArchivedStudent / "
                    + "tempFileCleanup）永远不会被触发，而应用本身不会有任何异常");
        }
        if (accessToken == null || accessToken.isBlank()) {
            // 空 token = 任何能访问 9999 端口的人都能让执行器跑任意已注册的 handler。
            // XXL-Job 历史上的 RCE 大多出在这里
            log.warn("XXL-Job accessToken 为空 —— 执行器端口 {} 对任何能访问它的来源都无鉴权。"
                    + "生产必须设 XXL_JOB_ACCESS_TOKEN，且执行器端口不得暴露到公网", port);
        }

        log.info("XXL-Job 执行器 appname={} port={} admin={} logPath={}（handler：{}）",
                appName, port,
                adminAddresses == null || adminAddresses.isBlank() ? "(未配置)" : adminAddresses,
                logPath + "/xxl-job",
                "anonymizeArchivedStudent, tempFileCleanup");
        return executor;
    }
}
