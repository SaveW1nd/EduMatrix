package com.edumatrix.common.file;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code sys_file.biz_type} 的值字典（<b>穷举，不得另造</b>）。
 *
 * <p>取值逐字来自 03-01 §7.1 的 {@code bizType} 参数说明与 {@code sys_file.biz_type} 的
 * DDL 注释（基线第 238 行），两处同源。
 *
 * <h2>本枚举同时承载四件事，都是安全语义，不是"元数据"</h2>
 * <ol>
 *   <li><b>能不能经 §7.1 上传</b>（{@link #uploadable}）。{@code fail_report} /
 *       {@code credential_sheet} / {@code export_report} 三个逐字「<b>由服务端异步任务生成登记，
 *       不经本接口上传</b>」 —— 不堵的话，学生可以自制一个 xlsx 标成
 *       {@code credential_sheet}，污染 {@code TempFileCleanupJob} 的白名单语义
 *       与 A22 页面的文件分类；</li>
 *   <li><b>谁能传</b>（{@link #allowedUserTypes}）。{@code bizType} 是 form 字段、<b>可伪造</b>，
 *       服务端必须按角色收窄；</li>
 *   <li><b>单文件上限</b>（{@link #maxSize}）。与扩展名档取 {@code min}；</li>
 *   <li><b>下载归属校验要不要 checker</b>（{@link #requiresOwnershipChecker}）与
 *       <b>其他接口能下发什么地址</b>（{@link #exposure}）—— 即 D-2 定案那张分档表。</li>
 * </ol>
 *
 * <h2>⚠ {@code MATERIAL_ATTACH} 被从「其余 bizType」挪进了需要 checker 的一档（B-3 定案）</h2>
 * <p>03-01 §7.3 原文把它归在「其余 bizType <b>本租户已登录用户可下载</b>」，而 03-03 §6.3
 * 要求学生看图文课时必须「该学生节点被显式授权该课程」，否则 {@code 20013}。
 * <b>同一份讲义，走课时接口要授权，走文件接口不要</b>；而 {@code fileId} 是雪花 ID，
 * 同租户内时间相邻、可近邻枚举（03-01 §7.2 自己警告过这一点）。
 *
 * <p>它现在没爆，只因为模块 08 还没建、库里没有 {@code material_attach} 行。
 * <b>模块 05 一上线这条路径就是通的，只等模块 08 往里放内容。</b>
 * 故按需方定案：在模块 11 的 {@code GrantChecker} 注册 checker 之前<b>一律 404</b>。
 * 代价是模块 08 期间讲义附件下不了 —— 「暂时下不了」优于「能下且不该下」。
 * 解除条件写在 {@code 04-实施计划.md} 模块 11 的「做完什么算做完」里。
 *
 * <p>这是<b>与 03-01 §7.3 原文的一处有意分叉</b>，已登记 F-38，不是漏读分册。
 */
public enum FileBizType {

    /** 头像。学生可传本人头像。 */
    AVATAR("avatar", true, UserTypes.ALL, FileConstants.MAX_SIZE_IMAGE, false, Exposure.SIGNED_INLINE),

    /** 课程封面（03-03 §1.3）。{@code coverUrl} 的来源。 */
    COURSE_COVER("course_cover", true, UserTypes.STAFF, FileConstants.MAX_SIZE_IMAGE, false,
            Exposure.SIGNED_INLINE),

    /** 图文资料内嵌图片（03-03 §4.3）。D-3：正文存 {@code fileId} 占位，出参重写为签名地址。 */
    MATERIAL_IMAGE("material_image", true, UserTypes.STAFF, FileConstants.MAX_SIZE_IMAGE, false,
            Exposure.SIGNED_INLINE),

    /** 图文资料附件。⚠ 见类注释 B-3：已挪进需要 checker 的一档。 */
    MATERIAL_ATTACH("material_attach", true, UserTypes.STAFF, FileConstants.MAX_SIZE_MATERIAL_ATTACH,
            true, Exposure.ID_ONLY),

    /** 学生导入源文件（03-02 接口 28）。仅 {@code org_admin} 发起导入。 */
    IMPORT_EXCEL("import_excel", true, UserTypes.ADMINS, FileConstants.MAX_SIZE_IMPORT_EXCEL, true,
            Exposure.ID_ONLY),

    /** 导入失败报告。服务端生成，不经 §7.1。 */
    FAIL_REPORT("fail_report", false, UserTypes.NONE, FileConstants.MAX_SIZE_DEFAULT, true, Exposure.ID_ONLY),

    /** 导入账号密码表（含明文初始密码）。服务端生成，不经 §7.1。 */
    CREDENTIAL_SHEET("credential_sheet", false, UserTypes.NONE, FileConstants.MAX_SIZE_DEFAULT, true,
            Exposure.ID_ONLY),

    /** 导出报表。服务端生成，不经 §7.1。03-05 §4.8 的 {@code downloadUrl} 由该接口自行签发。 */
    EXPORT_REPORT("export_report", false, UserTypes.NONE, FileConstants.MAX_SIZE_DEFAULT, true,
            Exposure.ID_ONLY),

    /**
     * 作答附件。<b>学生端唯一能传的业务附件</b>（03-01 §7.1 逐字「学生仅限作答附件等受限 bizType」）。
     *
     * <p><b>上限取 10MB 而非默认 100MB</b>：03-04 分册<b>全文没有任何一处</b>提到作答附件
     * （「附件 / attach / fileId」三个关键词零命中），{@code hw_answer_detail.student_answer}
     * 的 DDL 注释也只写「选项/填空文本/简答富文本」，<b>没有文件引用列</b>。
     * 即：{@code answer} 这个 bizType 在 03 六分册里<b>没有任何消费接口</b>。
     * 需方要求「找不到口径就登记待决项，不要自己拍一个数」——故这里<b>不发明新数字</b>，
     * 复用 03-01 §7.1 已有的「图片 10MB」那一档（作答附件的现实形态是拍照上传），
     * 并登记 <b>F-36</b>。20 次/分 × 10MB = 200MB/分，而按默认档是 2GB/分。
     */
    ANSWER("answer", true, UserTypes.STUDENT_ONLY, FileConstants.MAX_SIZE_IMAGE, true, Exposure.ID_ONLY),

    /**
     * 其他（默认值）。
     *
     * <p><b>不对学生开放</b>：03-01 §7.1 逐字「学生仅限作答附件<b>等</b>受限 bizType」，
     * 「等」字没有穷举。取最窄的读法 —— 学生只能传 {@link #ANSWER} 与 {@link #AVATAR}
     * （后者是本人头像）。放开 {@code common} 等于给学生一个 100MB 的通用上传口，
     * 而全库没有任何存储配额规定（D-5 定案本期不做配额）。已登记 <b>F-37</b>。
     */
    COMMON("common", true, UserTypes.STAFF, FileConstants.MAX_SIZE_DEFAULT, false, Exposure.ID_ONLY);

    /** {@code sys_user.user_type} 取值集合（契约 §3 角色表：0 超管 1 管理员 2 教师 3 学生）。 */
    private static final class UserTypes {
        static final Set<Integer> ALL = Set.of(0, 1, 2, 3);
        static final Set<Integer> STAFF = Set.of(0, 1, 2);
        static final Set<Integer> ADMINS = Set.of(0, 1);
        static final Set<Integer> STUDENT_ONLY = Set.of(3);
        static final Set<Integer> NONE = Collections.emptySet();
    }

    /** 其他接口（非 §7.3 下载接口）可以下发什么 —— D-2 定案的分档表。 */
    public enum Exposure {
        /**
         * 可下发 ≤30 分钟的<b>内联</b>签名地址。
         *
         * <p>只给必须由浏览器内联渲染的三种：{@code course_cover} / {@code material_image} /
         * {@code avatar}。理由是技术性的而非偏好：<b>{@code <img>} 标签带不了
         * {@code Authorization: Bearer} 头</b>（03-01 §7.3 的请求示例逐字带着它），
         * 改用 Cookie 认证又与 PRD §7.5 第 5 条「学生端<b>无 Cookie 依赖</b>，
         * 保证微信小程序 100% 复用」直接冲突。
         *
         * <p>这三种的 bizType 归属校验本来就只是「本租户已登录」（03-01 §7.3
         * 「其余 bizType 本租户已登录用户可下载」），所以签名地址绕过的不是归属校验，
         * 而只是「30 分钟内该地址可被转发到租户外」。
         */
        SIGNED_INLINE,
        /**
         * 只能返回 {@code fileId}（可带 {@code fileName} / {@code fileSize}），
         * <b>不得下发任何可访问地址</b>，一律走 03-01 §7.3。
         *
         * <p>{@code export_report} 的例外在 03-05 §4.8：该接口自己签发 {@code downloadUrl}，
         * 但它<b>先跑完自己的 {@code 40003}「任务不存在或非本人创建」归属校验</b>才签发 ——
         * 那是分册明写的形态，本枚举不管它。
         */
        ID_ONLY
    }

    private static final Map<String, FileBizType> BY_CODE;

    static {
        Map<String, FileBizType> map = new HashMap<>();
        for (FileBizType type : values()) {
            map.put(type.code, type);
        }
        BY_CODE = Collections.unmodifiableMap(map);
    }

    private final String code;
    private final boolean uploadable;
    private final Set<Integer> allowedUserTypes;
    private final long maxSize;
    private final boolean requiresOwnershipChecker;
    private final Exposure exposure;

    FileBizType(String code, boolean uploadable, Set<Integer> allowedUserTypes, long maxSize,
                boolean requiresOwnershipChecker, Exposure exposure) {
        this.code = code;
        this.uploadable = uploadable;
        this.allowedUserTypes = allowedUserTypes;
        this.maxSize = maxSize;
        this.requiresOwnershipChecker = requiresOwnershipChecker;
        this.exposure = exposure;
    }

    /** {@code sys_file.biz_type} 里存的字符串。 */
    public String code() {
        return code;
    }

    /** 能否经 03-01 §7.1 上传。false 的三个逐字「不经本接口上传」。 */
    public boolean uploadable() {
        return uploadable;
    }

    /** 允许上传的 {@code sys_user.user_type} 集合。 */
    public Set<Integer> allowedUserTypes() {
        return allowedUserTypes;
    }

    /** 本 bizType 的单文件上限（字节）。最终上限 = {@code min(本值, 扩展名档)}。 */
    public long maxSize() {
        return maxSize;
    }

    /**
     * 下载时是否需要一个 {@link FileOwnershipChecker}。
     *
     * <p><b>为 true 而注册表里没有 checker 时一律 404</b>（默认 DENY）——
     * 见 {@link FileOwnershipRegistry} 类注释。
     */
    public boolean requiresOwnershipChecker() {
        return requiresOwnershipChecker;
    }

    /** D-2 分档：其他接口能下发什么。 */
    public Exposure exposure() {
        return exposure;
    }

    /** 按 {@code sys_file.biz_type} 的字符串解析。未登记值返回 {@code empty}（调用方按 404 处理）。 */
    public static Optional<FileBizType> of(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code.trim()));
    }

    /** 全部需要 checker 的 bizType —— 供 {@link FileOwnershipRegistry} 自检与日志。 */
    public static Set<FileBizType> requiringChecker() {
        return Arrays.stream(values())
                .filter(FileBizType::requiresOwnershipChecker)
                .collect(() -> EnumSet.noneOf(FileBizType.class), Set::add, Set::addAll);
    }
}
