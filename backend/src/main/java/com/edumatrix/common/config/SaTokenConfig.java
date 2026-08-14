package com.edumatrix.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.edumatrix.auth.session.AuthSessionGuard;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;

/**
 * Sa-Token 装配与免登录白名单（05-工程结构.md §C2 / §G1，00-通用约定 §2.3）。
 *
 * <h2>白名单一共四条，一条不多</h2>
 * <table border="1">
 *   <caption>00-通用约定 §2.3</caption>
 *   <tr><th>接口</th><th>身份来源</th><th>由谁实现</th></tr>
 *   <tr><td>{@code POST /api/v1/auth/login}</td><td>请求体</td><td>模块 02</td></tr>
 *   <tr><td>{@code GET /api/v1/auth/captcha}</td><td>无</td><td>模块 02</td></tr>
 *   <tr><td>{@code POST /api/v1/auth/refresh}</td><td>请求体 refreshToken</td><td>模块 02</td></tr>
 *   <tr><td>{@code GET /api/v1/vod/decrypt-key}</td><td><b>URL 上的 MtsHlsUriToken</b></td><td>模块 12</td></tr>
 * </table>
 *
 * <h2>第四条的处理方式与前三条完全不同</h2>
 * <p>它<b>免登录但不免鉴权</b> —— 它才是加密视频真正的鉴权闸口。m3u8 与 TS 分片是 CDN 直出的，
 * {@code auth_key} 只防盗链、不防越权；播放器拿到密钥就能解密。若密钥接口不做与
 * {@code POST /api/v1/vod/play-auth} <b>逐条相同</b>的 {@code org_resource_grant} 校验，
 * 学生只要拿到 m3u8 地址，凭证过期、授权被撤销都拦不住他继续播放。
 *
 * <p><b>但那条校验不放在拦截器里</b>（05-工程结构.md §G1 的三条理由）：
 * <ol>
 *   <li>{@code MtsHlsUriToken} 同时是身份来源<b>和业务入参</b>，与该接口一一绑定，不是横切关注点；
 *   <li>它要跑的校验链在 Service 层（{@code PlayAuthChainService}），拦截器够不着；
 *       硬要够就会变成第二份校验逻辑，正是模块 12 明令禁止的；
 *   <li>放进拦截器等于在 Sa-Token 之外再建一套鉴权入口。免登录接口只有四条、
 *       其中三条不写业务表、第四条只读 —— <b>这个面越小越好</b>。
 * </ol>
 *
 * <h2>本类在模块 01 只铺骨架；模块 02 已按预告接上</h2>
 * <p>登录/登出/刷新令牌归模块 02；<b>冻结集校验</b>（每请求取 {@code node:anc:{myNodeId}}
 * 拆祖先链与 {@code frozen:{tenantId}} 求交，命中返回 {@code 10017}）也归模块 02，
 * 它会往这里的拦截器里加一段。模块 01 先把「拦截器装在哪、白名单排除哪几条」定死，
 * 免得模块 02 另起一套。
 *
 * <p><b>模块 02 的落地</b>：{@link AuthSessionGuard#check()} 在 {@code checkLogin()}
 * 之后执行两段业务校验 —— 冻结集（{@code 10017}）与强制改密门禁（403）。
 * 这里出现一处 {@code common → auth} 的 import，是<b>本类独有的例外</b>，
 * 05-工程结构.md §D 明写模块 02「同时触及 {@code common/config/}（Sa-Token 装配与白名单）」。
 * 它不违反 §A1 的检查③（那条约束的对象是<b>八个领域包之间</b>互相 import，
 * {@code common} 不在其中），也不与 {@code CurrentContextProvider} 那个 SPI 冲突 ——
 * 那个 SPI 存在的理由是<b>租户插件与 {@code TenantHelper} 跑在无 Spring 上下文的地方</b>
 * （MyBatis 拦截器内部），而本类是 Spring 装配代码，能直接注入 Bean。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 免登录路径（00-通用约定 §2.3 穷举四条）。
     *
     * <p><b>不要往这里加路径。</b>每加一条就是把一个入口暴露到鉴权之外，
     * 而契约 §2.8 的一条重要结论正是「去掉转码回调后，再没有一个免登录入口能写库」。
     */
    public static final String[] AUTH_WHITELIST = {
            "/api/v1/auth/login",
            "/api/v1/auth/captcha",
            "/api/v1/auth/refresh",
            "/api/v1/vod/decrypt-key",
    };

    /** 非业务路径：健康检查与指标抓取，不参与 {@code /api/v1} 的鉴权模型。 */
    private static final String[] INFRA_PATHS = {
            "/actuator/**",
            "/error",
    };

    private final AuthSessionGuard authSessionGuard;

    public SaTokenConfig(AuthSessionGuard authSessionGuard) {
        this.authSessionGuard = authSessionGuard;
    }

    /**
     * 一个拦截器，三段校验，顺序不可换：
     *
     * <ol>
     *   <li>{@code checkLogin()} —— 未登录/Token 失效 → 401；
     *   <li><b>冻结集</b> —— 本人节点或其上级被停用 → {@code 10017}（契约 §2.3）；
     *   <li><b>强制改密门禁</b> —— {@code pwd_reset_flag = 1} 访问其他接口 → 403。
     * </ol>
     *
     * <p>②在③之前：被停用的账号连「改密」这条路也不该走通 —— 反过来的话，
     * 一个所在分支已被冻结的账号还能改自己的密码。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    StpUtil.checkLogin();
                    authSessionGuard.check();
                }))
                .addPathPatterns("/**")
                .excludePathPatterns(AUTH_WHITELIST)
                .excludePathPatterns(INFRA_PATHS);
    }
}
