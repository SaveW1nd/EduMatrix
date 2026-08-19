package com.edumatrix.common.xss;

import java.util.regex.Pattern;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

/**
 * 富文本 XSS 白名单净化（PRD F2-2 规则 1、03-03 §4.3 说明）。
 *
 * <h2>过滤发生在<b>写入时</b>，不是读取时</h2>
 * <p>PRD F2-2 验收标准第 2 条逐字：「When 保存图文资料，Then 服务端
 * <b>过滤脚本标签后落库</b>，学生端渲染无脚本执行」——「后落库」三个字定死了时机。
 *
 * <p><b>只在输出过滤等于没做</b>：{@code crs_material.content} 至少有三条读出路径
 * （03-03 §4.2 管理端详情、§6.3 学生端图文课时、模块 14 的 H5 渲染），将来还会有导出。
 * 输出过滤要在每条路径上各做一次，漏一条就是一次存储型 XSS；
 * 而库里躺着的是带毒数据，任何绕过渲染层的消费都会重新暴露它。
 * <b>读取路径一律不做第二次过滤</b> —— 做了就是两份同源实现，
 * 而且会掩盖写入侧的缺陷（写入漏了也测不出来）。
 *
 * <p>本项目当前没有历史脏数据（{@code crs_material} 是新表，生产演示数据已于
 * {@code 49a3669} 清理），因此不需要回填清洗任务。
 *
 * <h2>白名单（默认拒绝：没列进来的标签、属性、协议一律剥离）</h2>
 * <table border="1">
 *   <caption>PRD F2-2 规则 1「支持图片、加粗、列表、表格」的逐项落地</caption>
 *   <tr><th>类别</th><th>标签</th><th>属性</th></tr>
 *   <tr><td>段落结构</td><td>p br hr div span h1~h6 blockquote pre code</td><td>—</td></tr>
 *   <tr><td>加粗与强调</td><td>b strong i em u s sub sup</td><td>—</td></tr>
 *   <tr><td>列表</td><td>ul ol li</td><td>—</td></tr>
 *   <tr><td>表格</td><td>table thead tbody tfoot tr th td caption</td><td>colspan rowspan（纯数字）</td></tr>
 *   <tr><td>图片</td><td>img</td><td>src（<b>只能是 {@code edumxfile:{fileId}} 占位</b>）、alt、width、height</td></tr>
 *   <tr><td>链接</td><td>a</td><td>href（https / http / mailto），强制 {@code rel="noopener noreferrer nofollow"}</td></tr>
 * </table>
 *
 * <p><b>明确不放行</b>：{@code script style link meta base iframe frame object embed applet
 * form input button select textarea svg math}；<b>全部 {@code on*} 事件属性</b>；
 * <b>{@code style} 属性整体不放行</b>（放行行内 CSS 会把 {@code expression()} 与
 * {@code url(javascript:)} 这一族攻击面重新打开，而 PRD 只要求加粗/列表/表格，不需要行内样式）。
 *
 * <h2>{@code <img src>} 为什么用正则谓词而不是协议白名单（D-3）</h2>
 * <p>D-3 定案：正文里存 {@code fileId} 占位，出参时由
 * {@code course/catalog/service/MaterialContentRewriter} 重写为 ≤30 分钟签名地址。
 * 于是 {@code src} 的合法取值<b>有且只有</b> {@code edumxfile:{雪花ID}} 这一种形状，
 * 用 {@link #IMG_SRC_PLACEHOLDER} 直接判形状比放行某个协议更严 ——
 * 任何外部 URL（含 {@code https://}）都不匹配，属性被整个丢掉。
 * 这同时落地了 PRD F2-2 规则 1 的「不允许外链」。
 *
 * <p>占位符协议名<b>不带连字符</b>（{@code edumxfile} 而非 {@code edumx-file}）：
 * 少一个会被各家解析器按不同规则处理的字符。
 *
 * <h2>输出形状是确定的，重写才敢用正则</h2>
 * <p>OWASP 的渲染器总是把属性写成 {@code name="escapedValue"}（双引号、值做 HTML 转义），
 * 而库里的 {@code content} <b>一律是本类的输出</b>（写入时过滤）。
 * 因此 {@code MaterialContentRewriter} 可以按 {@code src="edumxfile:(\d+)"} 精确替换，
 * 不必再解析一遍 DOM。{@code HtmlSanitizerTest} 钉住了这个形状 ——
 * 一旦渲染形式变了，那条断言先红。
 */
@Component
public class HtmlSanitizer {

    /** D-3 的正文图片占位符协议名。<b>改它要同时改 {@code MaterialContentRewriter}。</b> */
    public static final String FILE_PLACEHOLDER_SCHEME = "edumxfile";

    /** {@code <img src>} 的唯一合法形状：{@code edumxfile:{雪花ID}}。19 位是 long 的十进制上限。 */
    public static final Pattern IMG_SRC_PLACEHOLDER =
            Pattern.compile("^" + FILE_PLACEHOLDER_SCHEME + ":[0-9]{1,19}$");

    private static final Pattern NUMBER = Pattern.compile("^[0-9]{1,4}$");

    /** {@code <a href>} 只接受 http / https / mailto —— 占位符协议只给 {@code <img>}。 */
    private static final Pattern HREF_ALLOWED =
            Pattern.compile("^(?:https?://|mailto:)\\S+$", Pattern.CASE_INSENSITIVE);

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            // --- 段落结构 ---
            .allowElements("p", "br", "hr", "div", "span",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "blockquote", "pre", "code")
            // --- 加粗与强调（PRD F2-2 规则 1「加粗」）---
            .allowElements("b", "strong", "i", "em", "u", "s", "sub", "sup")
            // --- 列表（规则 1「列表」）---
            .allowElements("ul", "ol", "li")
            // --- 表格（规则 1「表格」）---
            .allowElements("table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption")
            .allowAttributes("colspan", "rowspan").matching(NUMBER).onElements("th", "td")
            // --- 图片（规则 1「图片」）：src 只能是 D-3 的 fileId 占位 ---
            .allowElements("img")
            .allowAttributes("src").matching(IMG_SRC_PLACEHOLDER).onElements("img")
            .allowAttributes("alt").onElements("img")
            .allowAttributes("width", "height").matching(NUMBER).onElements("img")
            // --- 链接 ---
            .allowElements("a")
            // 占位符协议必须一并放行：allowUrlProtocols 是【全局】的 URL 属性过滤，
            // 它作用在 href 与 src 上；不放行 edumxfile 会让上面那条 src 谓词永远拿不到值，
            // 表现是【所有内嵌图片被静默剥掉】——测试 stripsEventHandlers 抓到过这一次
            .allowUrlProtocols("https", "http", "mailto", FILE_PLACEHOLDER_SCHEME)
            // 反过来，href 不接受占位符协议：占位符是给 <img> 用的，
            // 放进链接只会产生一条点了没反应的死链，且让「正文里能不能出现 edumxfile」两处口径不一
            .allowAttributes("href").matching(HREF_ALLOWED).onElements("a")
            .requireRelNofollowOnLinks()
            .toFactory();

    /**
     * 净化富文本。{@code null} 原样返回 {@code null}（列可空）。
     *
     * <p><b>调用点只有一处</b>：{@code MaterialService} 在给
     * {@code crs_material.content} 赋值之前。新增第二个调用点之前先问一句
     * 那条路径是不是也应该走 {@code MaterialService}。
     */
    public String sanitize(String rawHtml) {
        return rawHtml == null ? null : POLICY.sanitize(rawHtml);
    }
}
