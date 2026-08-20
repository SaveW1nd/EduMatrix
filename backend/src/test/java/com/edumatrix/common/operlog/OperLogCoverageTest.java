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
 * <p>覆盖 {@code com.edumatrix.system}、{@code com.edumatrix.org} 与
 * {@code com.edumatrix.auth} 三个域 —— 当前全部写接口所在。
 *
 * <p><b>{@code auth} 曾经不在范围内，现已纳入。</b>需方定案后 03-01 §1.6 本人改密
 * 已补标 {@code @OperLog}；而把整个域纳入扫描、再用 {@link #EXEMPT} <b>逐个点名</b>
 * 三个豁免端点，比"整个域不扫"强一档：<b>将来 {@code auth} 新增一个写端点，
 * 它会红</b>，而"整个域不扫"对新增端点完全失明。
 *
 * <p>将来新增领域（{@code course} / {@code vod} / {@code question} / {@code homework} /
 * {@code stat}）时，<b>把包名加进 {@link #SCANNED_PACKAGES} 即可</b> ——
 * 不加的话本测试对那个域完全失明，这一点写在这里免得后来者以为它自动覆盖全库。
 */
class OperLogCoverageTest {

    /** 见类注释「扫描范围与它的边界」。新增领域时在这里加一行。 */
    private static final List<String> SCANNED_PACKAGES = List.of(
            "com.edumatrix.system", "com.edumatrix.org", "com.edumatrix.auth");

    /**
     * <b>逐个点名</b>的豁免端点。空集是理想状态；每加一项都必须在这里写清理由。
     *
     * <p>三个都在 {@code auth}：登录 / 刷新令牌属 {@code 00-通用约定} §2.3 的
     * <b>免登录白名单</b>，登出紧随其后；而「谁在什么时候登录了」已由
     * {@code sys_login_log} 承载（PRD F1-1：成功与失败都留痕）。
     * 再往 {@code sys_oper_log} 记一份，等于<b>同一件事两张表各存一份</b> ——
     * 那正是本项目反复点名的形态，且两份的口径迟早会分叉。
     *
     * <p><b>不豁免 {@code changePassword}</b>：改密是安全相关事件，
     * 落在 PRD §7.3 第 7 条「敏感操作记 {@code sys_oper_log}」里（需方定案）。
     */
    private static final Set<String> EXEMPT = Set.of(
            "AuthController#login",
            "AuthController#refresh",
            "AuthController#logout");

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
                String id = controller.getSimpleName() + "#" + method.getName();
                if (method.getAnnotation(OperLog.class) == null && !EXEMPT.contains(id)) {
                    missing.add(id);
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
     * <p>当前 45 = {@code org} 22（member 15 + node 4 + <b>grant 3</b>）+ {@code system} 19
     * （user 5 / role 4 / menu 3 / tenant 5 / tenantConfig 1 / <b>file 1</b>）
     * + {@code auth} 4（login / refresh / logout / changePassword，
     * 其中前三个在 {@link #EXEMPT} 里）。
     * 新增写接口时本条会红 —— <b>那正是提醒去标注解的时刻</b>，请连同数字一起改。
     *
     * <p><b>它已经真的红过三次</b>：模块 05 的 C3 把数字钉在 37，C4 加了
     * {@code SysFileController#upload}（03-01 §7.1 上传文件）之后立刻红，改成 38；
     * 随后 {@code auth} 域纳入扫描（§1.6 补标）又红一次，改成 42；
     * 模块 11 的 C4 加了 {@code OrgGrantController#grant}（03-02 §9.2 授权资源给节点）
     * 第三次红，改成 43；C5 加了 {@code #revoke}（§9.3 撤销资源授权）第四次红，改成 44；
     * C6 加了 {@code #updateValidity}（§9.4 修改授权有效期）第五次红，改成 45
     * —— 而 PRD FR-1 规则 9 逐字要求「所有<b>授权/撤销</b>写 {@code sys_oper_log}」，
     * 这两红正好各落在那条要求的一半上。
     * 这就是它存在的样子。
     *
     * <p><b>⚠ 「标了注解」不等于「日志内容达标」，本条只管前者。</b>
     * 切面序列化的是<b>入参</b>；当某条规则要求日志里含<b>结果</b>时，
     * 光标注解是不够的 —— 那要由领域侧再写一条
     *（{@code MemberOperLogWriter} / {@code GrantOperLogWriter} 就是干这个的，
     * 且那两个端点<b>同样标着</b> {@code @OperLog}，一次请求两条日志、各记一个事实）。
     *
     * <p>已知的两处：模块 07 的「监护人同意留痕」（PRD F7-1）、
     * 模块 11 的「撤销影响面留痕」（04 §B 规则 17 / PRD FR-4 规则 7 要的
     * 级联节点数与学员数）。<b>本测试对它们全绿，但它验的不是那件事</b> ——
     * 写在这里是因为：本条全绿最容易让人以为「操作日志这块齐了」。
     */
    @Test
    @DisplayName("写端点总数 = 45（新增写接口时本条会红，提醒去标 @OperLog）")
    void writeEndpointCountIsPinned() throws Exception {
        int count = 0;
        for (Class<?> controller : scanControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isWriteEndpoint(method)) {
                    count++;
                }
            }
        }
        assertThat(count).isEqualTo(45);
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
