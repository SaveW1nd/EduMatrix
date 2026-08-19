package com.edumatrix.common.operlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>每一个写接口都必须标 {@code @OperLog}</b> —— 这条测试守的是"下一个人"。
 *
 * <h2>为什么需要它</h2>
 * <p>切面到位之后，「有没有操作日志」这件事变成了<b>一个注解的有无</b>。
 * 漏标一个写接口的表现是：接口 200、功能正常、只是这次操作在
 * {@code sys_oper_log} 里查不到 —— <b>没有任何东西会报错</b>，
 * 而发现它的时机通常是「出事之后想查是谁干的」。
 * 这正是本项目反复点名的头号故障形态。
 *
 * <p>本模块补标 {@code system/} 18 处时就是这么发现缺口的：模块 03/04 落地时
 * 切面还不存在，注解自然没人标；03-01 §8.2 的响应示例逐字给的是
 * {@code "method": "PUT /api/v1/system/users/.../password/reset"}，
 * 而那个接口当时<b>一个注解都没有</b> —— 分册自己的示例永远不会出现。
 *
 * <h2>它会不会红</h2>
 * <p>把任意一个 {@code @PostMapping} / {@code @PutMapping} / {@code @DeleteMapping}
 * 上的 {@code @OperLog} 删掉 → 立刻红，且报出具体是哪个类的哪个方法。
 * 新增一个写接口而忘了标 → 同样红。
 *
 * <h2>扫描范围与它的边界</h2>
 * <p>覆盖 {@code com.edumatrix.system} 与 {@code com.edumatrix.org} 两个域 ——
 * 当前全部写接口所在。<b>{@code auth} 域刻意不在范围内</b>，理由逐条：
 * 它的四个写端点是登录 / 登出 / 刷新令牌 / 本人改密，前三个属
 * {@code 00-通用约定} §2.3 免登录白名单且已由 {@code sys_login_log} 承载；
 * 第四个（03-01 §1.6 本人改密）值得记，但<b>不在模块 05 的工单授权范围内</b>，
 * 已在交付报告中作为建议提出，未擅自标注。
 *
 * <p>将来新增领域（{@code course} / {@code vod} / {@code question} / {@code homework} /
 * {@code stat}）时，<b>把包名加进 {@link #SCANNED_PACKAGES} 即可</b> ——
 * 不加的话本测试对那个域完全失明，这一点写在这里免得后来者以为它自动覆盖全库。
 */
class OperLogCoverageTest {

    /** 见类注释「扫描范围与它的边界」。新增领域时在这里加一行。 */
    private static final List<String> SCANNED_PACKAGES = List.of(
            "com.edumatrix.system", "com.edumatrix.org");

    private static final Set<Class<?>> WRITE_MAPPINGS =
            Set.of(PostMapping.class, PutMapping.class, DeleteMapping.class);

    @Test
    @DisplayName("system / org 两域的每个写端点都标了 @OperLog（漏标或新增未标都会红）")
    void everyWriteEndpointCarriesOperLog() throws Exception {
        List<String> missing = new ArrayList<>();
        int checked = 0;

        for (Class<?> controller : scanControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isWriteEndpoint(method)) {
                    continue;
                }
                checked++;
                if (method.getAnnotation(OperLog.class) == null) {
                    missing.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(checked)
                .as("一个写端点都没扫到 —— 扫描本身失效了，这条测试就成了空转的绿灯")
                .isGreaterThan(0);
        assertThat(missing)
                .as("下列写端点没有 @OperLog：操作会成功、日志里查不到、没有任何东西报错")
                .isEmpty();
    }

    /**
     * 计数护栏：钉住当前的写端点总数。
     *
     * <p>与上面那条是<b>不同的东西</b>：上面验"已扫到的都标了"，这一条验"扫到的数量对不对"。
     * 如果哪天扫描逻辑本身退化（比如注解元数据丢失、包名改了），
     * 上面那条会以"零个未标注"的姿态<b>全绿</b>，而本条会红。
     *
     * <p>当前 37 = {@code org} 19（member 15 + node 4）+ {@code system} 18
     * （user 5 / role 4 / menu 3 / tenant 5 / tenantConfig 1）。
     * 新增写接口时本条会红 —— <b>那正是提醒去标注解的时刻</b>，请连同数字一起改。
     */
    @Test
    @DisplayName("写端点总数 = 37（新增写接口时本条会红，提醒去标 @OperLog）")
    void writeEndpointCountIsPinned() throws Exception {
        int count = 0;
        for (Class<?> controller : scanControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isWriteEndpoint(method)) {
                    count++;
                }
            }
        }
        assertThat(count).isEqualTo(37);
    }

    private static boolean isWriteEndpoint(Method method) {
        return WRITE_MAPPINGS.stream().anyMatch(a -> method.getAnnotation(asAnnotation(a)) != null);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends java.lang.annotation.Annotation> asAnnotation(Class<?> type) {
        return (Class<? extends java.lang.annotation.Annotation>) type;
    }

    private static List<Class<?>> scanControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllers = new ArrayList<>();
        for (String pkg : SCANNED_PACKAGES) {
            for (BeanDefinition definition : scanner.findCandidateComponents(pkg)) {
                controllers.add(Class.forName(definition.getBeanClassName()));
            }
        }
        return controllers;
    }
}
