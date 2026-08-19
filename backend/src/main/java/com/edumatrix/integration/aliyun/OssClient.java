package com.edumatrix.integration.aliyun;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.BucketInfo;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import com.edumatrix.common.file.ObjectStorage;

/**
 * 阿里云 OSS 客户端（{@code sys_file.storage = 2}）。模块 05 的部署级配置<b>全部</b>消费方
 * （05-工程结构.md §G2：「任何业务包里出现 {@code @Value("${ALIYUN_...}")} 都是越界」）。
 *
 * <h2>两个 endpoint，不是一个</h2>
 * <table border="1">
 *   <caption>两个客户端的分工</caption>
 *   <tr><th>客户端</th><th>endpoint</th><th>干什么</th><th>配错了会怎样</th></tr>
 *   <tr><td>{@code internalOss}</td><td>{@code oss-cn-*-internal.aliyuncs.com}</td>
 *       <td>PutObject / DeleteObject / GetObject / GetBucketInfo</td>
 *       <td>响亮失败（连不上）</td></tr>
 *   <tr><td>{@code publicOss}</td><td>{@code oss-cn-*.aliyuncs.com}</td>
 *       <td><b>只签名，不发请求</b></td>
 *       <td>响亮失败（客户端 DNS 解析不到内网域名）</td></tr>
 * </table>
 * <p>同地域走内网<b>不计流量费</b>且不出公网；而签名地址是给浏览器用的，必须是公网域名。
 * 用一个客户端做两件事，必然有一件是错的。
 *
 * <h2>启动自检：两条合规基线第一次有代码承载</h2>
 * <ol>
 *   <li><b>桶 ACL 必须是 {@code Private}</b>（{@code 00-通用约定} §7.4「对象存储桶权限
 *       一律<b>私有</b>」）。不自检的话，桶被改成公共读时<b>系统一切正常</b> ——
 *       没有任何东西会失败，而全部课程封面、讲义、导出报表当场对全互联网敞开。
 *       这正是本项目已出现四次的「以为存在、实际从未生效的保障」；</li>
 *   <li><b>Location 必须以 {@code oss-cn-} 开头</b>（契约 §7.2 第 4 条「存储区域<b>仅中国大陆</b>」、
 *       §1 区域约束表「不得开启任何境外节点」）。<b>这条此前全库零承载</b> ——
 *       它只是一句写在契约里的话，运维在控制台选了新加坡不会有任何东西报错。</li>
 * </ol>
 * <p>两条都<b>让应用启动失败</b>，不是 WARN。契约 §9.3 的写作纪律：写不出验收标准的条款等于不存在；
 * 而一条只打 WARN 的合规检查，在一个日志滚动的生产环境里等于不存在。
 *
 * <p><b>只调 {@code getBucketInfo} 一次，不调 {@code getBucketAcl}</b>：需方配好的 RAM 策略
 * 只授了 {@code Put/Get/Delete/AbortMultipartUpload/ListParts + ListObjects/GetBucketInfo}，
 * <b>没有 {@code oss:GetBucketAcl}</b>。而 {@code BucketInfo} 同时带 {@code CannedACL} 与
 * {@code Location} —— 一次调用、一个权限，两条都验到。
 *
 * <h2>AK/SK</h2>
 * <p>来自 {@code /etc/edumatrix/db.env}（{@code edumatrix.service} 的 {@code EnvironmentFile}，
 * systemd 读它<b>不写 journal</b>），RAM 子账号、最小权限。
 * <b>本类不打印任何凭据</b>：启动日志只出 bucket、endpoint、location 三项，都是非敏感值。
 * 异常也只记 OSS 的 {@code requestId} 与 {@code errorCode}，<b>不记 message 原文</b> ——
 * SDK 的部分异常 message 会把待签名串回显出来。
 */
@Component("ossClient")
@ConditionalOnExpression("'${edumatrix.file.oss.bucket:}'.trim() != ''")
public class OssClient implements ObjectStorage, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OssClient.class);

    /** 契约 §7.2 第 4 条「存储区域仅中国大陆」——中国大陆地域的 Location 一律 {@code oss-cn-*}。 */
    private static final String MAINLAND_LOCATION_PREFIX = "oss-cn-";

    /**
     * 下载档强制的响应 MIME。
     *
     * <p>不用真实 MIME：{@code nosniff} 在 302 路径上拿不到（D-4），
     * 而 {@code application/octet-stream} 是"浏览器无论如何都不会内联渲染"的那一个。
     */
    static final String ATTACHMENT_CONTENT_TYPE = "application/octet-stream";

    private final String bucket;
    /** 配置里那两个 endpoint 的原值 —— 启动自检要拿它们与桶自报的值比对。 */
    private final String configuredInternalEndpoint;
    private final String configuredPublicEndpoint;
    private final OSS internalOss;
    private final OSS publicOss;

    public OssClient(@Value("${edumatrix.file.oss.bucket}") String bucket,
                     @Value("${edumatrix.file.oss.internal-endpoint}") String internalEndpoint,
                     @Value("${edumatrix.file.oss.public-endpoint}") String publicEndpoint,
                     @Value("${edumatrix.file.oss.access-key-id}") String accessKeyId,
                     @Value("${edumatrix.file.oss.access-key-secret}") String accessKeySecret) {
        this.bucket = bucket.trim();
        this.configuredInternalEndpoint = internalEndpoint.trim();
        this.configuredPublicEndpoint = publicEndpoint.trim();
        this.internalOss = new OSSClientBuilder().build(configuredInternalEndpoint, accessKeyId, accessKeySecret);
        this.publicOss = new OSSClientBuilder().build(configuredPublicEndpoint, accessKeyId, accessKeySecret);
        assertBucketIsPrivateAndInMainland();
    }

    /**
     * 见类注释「启动自检」。抛异常即启动失败 —— 契约 §J1 是单实例部署，
     * 启动失败是<b>看得见</b>的（systemd 反复重启 + 日志），而配错的私有桶是看不见的。
     */
    private void assertBucketIsPrivateAndInMainland() {
        BucketInfo info;
        try {
            info = internalOss.getBucketInfo(bucket);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "OSS 桶自检失败：读不到 bucket=" + bucket + " 的信息。请确认 RAM 策略含 oss:GetBucketInfo、"
                            + "endpoint 与桶同地域、AK/SK 有效。" + describe(e), e);
        }

        CannedAccessControlList acl = info.getCannedACL();
        if (acl != CannedAccessControlList.Private) {
            throw new IllegalStateException(
                    "OSS 桶 ACL 必须为 Private，实际为 " + acl + "（00-通用约定 §7.4：对象存储桶权限一律私有）。"
                            + "拒绝启动 —— 公共读的桶会让全部课程封面、讲义与导出报表对全互联网敞开，"
                            + "而这件事不会有任何其他东西报错。bucket=" + bucket);
        }

        String location = info.getBucket() == null ? null : info.getBucket().getLocation();
        if (location == null || !location.startsWith(MAINLAND_LOCATION_PREFIX)) {
            throw new IllegalStateException(
                    "OSS 桶存储区域必须在中国大陆（Location 应形如 oss-cn-*），实际为 " + location
                            + "（DESIGN-CONTRACT §7.2 第 4 条「不得开启任何境外节点」、§1 区域约束表）。"
                            + "新增任何存储区域前必须先完成该区域的事件通知配置与个保法第 38 条出境合规评审。"
                            + " bucket=" + bucket);
        }

        // ③ 跨地域检查：见类注释「第 ③ 条为什么不能靠"名字里有 oss-cn-"来判」
        String bucketIntranet = info.getBucket().getIntranetEndpoint();
        if (!hostOf(configuredInternalEndpoint).equals(hostOf(bucketIntranet))) {
            throw new IllegalStateException(
                    "OSS 内网 endpoint 与桶不匹配：配置 " + hostOf(configuredInternalEndpoint)
                            + "，而 bucket=" + bucket + "（location=" + location + "）的内网 endpoint 是 "
                            + hostOf(bucketIntranet) + "。"
                            + "两种成因都要拒绝启动：① 桶与 ECS 跨地域 —— 流量走公网，计费、延迟都变，"
                            + "而且完全静默；② 内网那一格被填成了公网 endpoint —— 那会把「跨地域会连不上」"
                            + "这个响亮的信号消掉，变成每个月账单上多出来的一笔。"
                            + "正确做法是把桶建在与 ECS 同一地域，并把 ALIYUN_OSS_INTERNAL_ENDPOINT 填成上面那个值。");
        }

        // 公网 endpoint 只 WARN 不拒绝：将来绑自有域名（如 file.hqtw.cn，需备案）时
        // 它本来就会与桶的默认外网 endpoint 不同，作硬闸会把那条正常路径堵死
        String bucketExtranet = info.getBucket().getExtranetEndpoint();
        if (!hostOf(configuredPublicEndpoint).equals(hostOf(bucketExtranet))) {
            log.warn("OSS 公网 endpoint 与桶的默认外网 endpoint 不同：配置 {}，桶默认 {}。"
                            + "绑了自有加速域名时这是正常的；否则请核对 —— 签名地址是给浏览器用的，"
                            + "填错会让客户端解析不到（响亮失败）",
                    hostOf(configuredPublicEndpoint), hostOf(bucketExtranet));
        }

        log.info("对象存储 = 阿里云 OSS bucket={} location={} acl={} intranet={}（storage 将写 2）",
                bucket, location, acl, hostOf(bucketIntranet));
    }

    @Override
    public int storageType() {
        return 2;
    }

    @Override
    public void put(String key, Path source, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        // 对象元数据里也写一份 attachment：即使将来有人绕过签名参数直接生成地址，
        // 默认响应仍是下载而不是内联渲染。签名参数是主线，这一层是兜底。
        metadata.setContentDisposition("attachment");
        try {
            internalOss.putObject(bucket, key, source.toFile(), metadata);
        } catch (RuntimeException e) {
            throw new IllegalStateException("OSS 上传失败 key=" + key + " " + describe(e), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            internalOss.deleteObject(bucket, key);
        } catch (RuntimeException e) {
            throw new IllegalStateException("OSS 删除失败 key=" + key + " " + describe(e), e);
        }
    }

    /**
     * 签名地址。<b>用 {@code publicOss} 签，且这一步不发任何网络请求</b>。
     *
     * <p>两个 override 参数<b>参与签名</b>（OSS 把 {@code response-*} 当作要签的子资源），
     * 所以客户端改一个字签名就失效。这是 D-4 定案在 302 路径上唯一能拿到的抓手 ——
     * {@code X-Content-Type-Options: nosniff} 在 302 之后由 OSS 响应，我们下发不了，
     * 需方已知悉并接受。
     */
    @Override
    public Optional<String> presignedUrl(String key, String downloadFileName, String contentType,
                                         Disposition disposition, Duration ttl) {
        GeneratePresignedUrlRequest request =
                presignRequest(bucket, key, downloadFileName, contentType, disposition, ttl);
        return Optional.of(publicOss.generatePresignedUrl(request).toString());
    }

    /**
     * 组装签名请求（含 D-4 的两个 override 参数）。
     *
     * <p><b>拆成 static 是为了可测</b>：本类的构造函数会做启动自检（要连 OSS），
     * 测试里构造不出实例；而 D-4 那两个参数在不在、值对不对，是必须被测到的东西 ——
     * 它们是「00-通用约定 §7.4 的下载头基线在 302 路径上唯一的抓手」。
     * {@code OssPresignParamsTest} 直接调它，并用一个一次性 OSS 客户端签出真实 URL
     * 断言参数确实落在了地址里（{@code generatePresignedUrl} 是本地签名、不发网络请求）。
     */
    static GeneratePresignedUrlRequest presignRequest(String bucket, String key, String downloadFileName,
                                                      String contentType, Disposition disposition,
                                                      Duration ttl) {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key);
        request.setExpiration(new Date(System.currentTimeMillis() + ttl.toMillis()));

        ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
        if (disposition == Disposition.ATTACHMENT) {
            // octet-stream + attachment：nosniff 缺席时，这对组合是阻止浏览器内联渲染的主力
            overrides.setContentType(ATTACHMENT_CONTENT_TYPE);
            overrides.setContentDisposition("attachment; " + rfc5987(downloadFileName));
        } else {
            // D-2 的三种内联档：给真实 MIME + inline。显式写 inline 而不是"什么都不设"——
            // 中国大陆地域桶的默认域名对部分类型会自行强制下载，不显式覆盖就是听天由命
            overrides.setContentType(contentType);
            overrides.setContentDisposition("inline; " + rfc5987(downloadFileName));
        }
        request.setResponseHeaders(overrides);
        return request;
    }

    @Override
    public InputStream openStream(String key) {
        try {
            return internalOss.getObject(bucket, key).getObjectContent();
        } catch (RuntimeException e) {
            throw new IllegalStateException("OSS 读取失败 key=" + key + " " + describe(e), e);
        }
    }

    @Override
    public void destroy() {
        internalOss.shutdown();
        publicOss.shutdown();
    }

    /**
     * 取 endpoint 的<b>主机名</b>用于比对：剥掉 {@code http(s)://} 前缀、结尾斜杠，转小写。
     *
     * <p>不做这层归一的话，{@code https://oss-cn-hangzhou-internal.aliyuncs.com} 与
     * {@code oss-cn-hangzhou-internal.aliyuncs.com} 会被判成不同 ——
     * 而两者都是<b>正确</b>的配置写法，那样自检就会变成一个只会误报的东西，
     * 最后被人加个开关关掉。
     */
    static String hostOf(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String host = endpoint.trim().toLowerCase(java.util.Locale.ROOT);
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host;
    }

    /** RFC 5987 的 {@code filename*}，与 03-01 §7.3 的响应头示例同格式（{@code filename*=UTF-8''...}）。 */
    private static String rfc5987(String fileName) {
        String safe = fileName == null || fileName.isBlank() ? "download" : fileName;
        return "filename*=UTF-8''" + URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 只取异常的类型与 {@code requestId}，<b>不取 message</b>。
     *
     * <p>反射取 {@code getRequestId()} 是为了不把 {@code OSSException} 的类型写进签名 ——
     * 那会让本类的异常处理与 SDK 版本耦合；而这里要的只是一个可以拿去提工单的 ID。
     */
    private static String describe(RuntimeException e) {
        String requestId = "";
        try {
            Object id = e.getClass().getMethod("getRequestId").invoke(e);
            requestId = id == null ? "" : String.valueOf(id);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 不是 OSSException，没有 requestId —— 正常情况，不记
        }
        return "[" + e.getClass().getSimpleName() + " requestId=" + requestId + "]";
    }
}
