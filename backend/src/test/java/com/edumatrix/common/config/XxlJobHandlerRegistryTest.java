package com.edumatrix.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;

/**
 * XXL-Job 的两件事：<b>执行器真的被装配了</b>，<b>handler 名字互不重复且被钉住</b>。
 *
 * <h2>这条测试守的是一个刚发生过的真实缺口</h2>
 * <p>模块 01 起 {@code application.yml} 就有 {@code xxl.job.*} 五个配置项、
 * {@code deploy/.env.example} 有 {@code XXL_JOB_ENABLED=false}，读起来像是「置 true 即可启用」；
 * 而全库<b>没有 {@code XxlJobSpringExecutor} 这个 Bean</b> —— 置 true 也不会有任何事发生，
 * 两个 {@code @XxlJob} 注解<b>是惰性的</b>。这个状态从模块 01 一直活到模块 05 之后，
 * 期间没有任何检查、测试或启动日志提到过它。
 *
 * <h2>{@link #handlerNamesArePinned} 才是这一组的重点</h2>
 * <p>XXL-Job 的任务必须<b>在调度中心手工登记</b>（handler 名 + Cron）才会被触发。
 * 代码里加一个 {@code @XxlJob} 而忘了去调度中心登记，后果是：
 * <b>编译过、启动过、执行器注册成功、任务永远不跑</b>，且没有任何一处会报告它 ——
 * 这正是本项目定义的头号故障形态。
 *
 * <p>把 handler 名集合钉死，就把「加了一个 Job」变成一次<b>必须有人动手改测试</b>的事件，
 * 而改测试的那一刻正是去调度中心登记的提醒。
 * <b>新增 Job 时请连同 {@code 05-工程结构.md} §H 的目录清单一起更新。</b>
 */
class XxlJobHandlerRegistryTest {

    /**
     * 当前全部 {@code @XxlJob} handler 名。
     *
     * <p>加一个 Job → 本测试红 → 去调度中心登记 → 回来改这里。顺序即提醒顺序。
     */
    private static final Set<String> EXPECTED_HANDLERS = new TreeSet<>(Set.of(
            "anonymizeArchivedStudent",   // 模块 07  每日一次   删除请求脱敏（PRD F7-3）
            "tempFileCleanup"));          // 模块 05  每日 03:30 敏感文件 7 天保留期清理（§7.4）

    @Test
    @DisplayName("XxlJobConfig 里确实有一个产出 XxlJobSpringExecutor 的 @Bean 方法")
    void executorBeanMethodExists() {
        List<Method> beanMethods = java.util.Arrays.stream(XxlJobConfig.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(org.springframework.context.annotation.Bean.class) != null)
                .filter(m -> XxlJobSpringExecutor.class.isAssignableFrom(m.getReturnType()))
                .toList();

        assertThat(beanMethods)
                .as("XxlJobConfig 被掏空了 —— 那就退回到模块 05 之前的状态："
                        + "xxl.job.* 配置项没有任何东西在读，两个 @XxlJob 注解是惰性的")
                .hasSize(1);

        // start / destroy 必须挂上：不挂 start，执行器建出来了却不注册，
        // 表现是调度中心里执行器列表恒为空，而应用侧毫无异常
        org.springframework.context.annotation.Bean bean =
                beanMethods.get(0).getAnnotation(org.springframework.context.annotation.Bean.class);
        assertThat(bean.initMethod()).isEqualTo("start");
        assertThat(bean.destroyMethod()).isEqualTo("destroy");
    }

    @Test
    @DisplayName("执行器 Bean 受 xxl.job.enabled 门控（去掉条件会让 dev/test 每几秒刷一条连不上的 ERROR）")
    void executorIsGatedByEnabledFlag() {
        ConditionalOnProperty condition =
                XxlJobConfig.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition)
                .as("没有 @ConditionalOnProperty 的话，集成测试也会去连调度中心")
                .isNotNull();
        assertThat(condition.name()).contains("xxl.job.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing())
                .as("默认必须是关的 —— 缺配置时不该自作主张启用一个会向外连的组件")
                .isFalse();
    }

    @Test
    @DisplayName("handler 名不重复（重名时 XXL-Job 只有一个生效，另一个静默失效）")
    void handlerNamesAreUnique() {
        List<String> names = scanHandlerNames();

        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).as("一个 @XxlJob 都没扫到 —— 扫描失效了，本组测试成了空转的绿灯")
                .isNotEmpty();
    }

    @Test
    @DisplayName("handler 名集合 = 已在调度中心登记的那一份（加 Job 会红，提醒去登记）")
    void handlerNamesArePinned() {
        assertThat(new TreeSet<>(scanHandlerNames()))
                .as("代码里的 @XxlJob 与本清单不一致。新增 Job 时：① 去调度中心登记 handler 名与 Cron；"
                        + "② 回来更新本清单；③ 同步 05-工程结构.md §H 的目录。"
                        + "漏了 ① 的后果是任务永远不跑，且没有任何一处会报告它")
                .isEqualTo(EXPECTED_HANDLERS);
    }

    private static List<String> scanHandlerNames() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        List<String> names = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.edumatrix")) {
            for (Method method : loadClass(definition.getBeanClassName()).getDeclaredMethods()) {
                XxlJob handler = method.getAnnotation(XxlJob.class);
                if (handler != null) {
                    names.add(handler.value());
                }
            }
        }
        return names;
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            throw new IllegalStateException("扫描到但加载不了：" + name, e);
        }
    }
}
