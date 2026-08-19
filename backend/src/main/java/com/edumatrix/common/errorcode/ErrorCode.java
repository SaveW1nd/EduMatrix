package com.edumatrix.common.errorcode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局错误码枚举，<b>逐码对齐 {@code docs/03-API接口文档/00-通用约定.md} §9 错误码总表</b>。
 *
 * <p><b>登记册在文档、不在代码</b>：新增错误码必须<b>先在 §9 登记再在分册使用</b>（契约 §6.3）。
 * 本枚举是那张表的机械副本，不是它的权威。两边不一致由
 * {@code ErrorCodeRegistryTest} 抓 —— 那个测试直接解析 §9 的表格，
 * 断言「文档里的码集合 == 本枚举的码集合」，逐码比对含义。
 *
 * <p><b>为什么要那个测试</b>：一致性检查器的 C3 比对的是各分册与登记册这两份<b>文档</b>，
 * 它看不见代码里的字面量（05-工程结构.md §E 明写这一条）。业务代码里写
 * {@code R.fail(10305)} 这样的裸数字，C3 抓不到，本枚举也拦不住 ——
 * 所以纪律是：<b>只用枚举项，不写字面量</b>。
 *
 * <p><b>HTTP 状态码与响应体 code 的关系</b>（00-通用约定 §3.3）：
 * <ul>
 *   <li>{@link Layer#FRAMEWORK} 框架层错误 —— HTTP 状态码与响应体 {@code code} <b>一致</b>；
 *   <li>{@link Layer#BUSINESS} 业务错误（1xxxx~4xxxx）—— HTTP 状态码统一 <b>200</b>，
 *       以响应体 {@code code} 判定业务结果。
 * </ul>
 *
 * <p><b>越界拒绝的三分法</b>（契约 §2.4 / 00-通用约定 §2.4，全系统统一，各分册不得另立规则）：
 * <ul>
 *   <li>「我要操作的东西」越界（路径上的 {@code /xxx/{id}} 不在我的子树内）→ {@link #NOT_FOUND} 404，不暴露存在性；
 *   <li>「我选的目标」越界（请求体/参数里显式指定的 {@code targetParentId}、{@code studentIds} 等）
 *       → {@link #TARGET_NODE_OUT_OF_SCOPE} 10107，HTTP 200，需明确提示"请重新选择"；
 *   <li>「我没资格做这件事」（角色/菜单权限不足）→ {@link #FORBIDDEN} 403。
 * </ul>
 */
public enum ErrorCode {

    // ========================================================================
    // §9.1 HTTP / 框架层 —— HTTP 状态码与响应体 code 一致
    // ========================================================================
    /** 成功 */
    SUCCESS(200, "操作成功", Layer.FRAMEWORK),
    /** 参数校验失败（缺参、格式错误、超长、枚举值非法）；msg 中返回具体字段错误 */
    BAD_REQUEST(400, "参数校验失败", Layer.FRAMEWORK),
    /** 未登录或 accessToken 已过期/无效 */
    UNAUTHORIZED(401, "未登录或登录已过期", Layer.FRAMEWORK),
    /** 已登录但无该功能权限（含数据权限拒绝的写操作） */
    FORBIDDEN(403, "无操作权限", Layer.FRAMEWORK),
    /** 资源不存在，或跨租户访问（不暴露存在性）；路径上的资源越界亦返回本码 */
    NOT_FOUND(404, "数据不存在或已删除", Layer.FRAMEWORK),
    /** 请求方法不支持 */
    METHOD_NOT_ALLOWED(405, "请求方法不支持", Layer.FRAMEWORK),
    /** 触发限流；客户端按 Retry-After 退避 */
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试", Layer.FRAMEWORK),
    /** 服务端内部异常 */
    INTERNAL_ERROR(500, "系统繁忙，请稍后再试", Layer.FRAMEWORK),

    // ========================================================================
    // §9.2 业务错误码 1xxxx —— 系统与组织
    // 基础段 10001~10017（10010、10018~10099 为预留号段）
    // ========================================================================
    USERNAME_ALREADY_EXISTS(10001, "用户名已存在", Layer.BUSINESS),
    IMPORT_FILE_FORMAT_INVALID(10002, "导入文件格式错误", Layer.BUSINESS),
    /** 登录失败不区分具体哪项错误，防撞库探测 */
    USERNAME_OR_PASSWORD_WRONG(10003, "用户名或密码错误", Layer.BUSINESS),
    CAPTCHA_WRONG_OR_EXPIRED(10004, "验证码错误或已过期", Layer.BUSINESS),
    /** sys_user.status=1（账号级封禁，仅超管可置），或连续密码错误触发锁定。与 10017 是两个不同原因 */
    ACCOUNT_DISABLED_OR_LOCKED(10005, "账号已停用或已锁定", Layer.BUSINESS),
    REFRESH_TOKEN_INVALID(10006, "refreshToken 无效或已过期", Layer.BUSINESS),
    TENANT_DISABLED_OR_EXPIRED(10007, "租户已停用或服务已到期", Layer.BUSINESS),
    ROLE_IN_USE(10008, "角色已被用户引用，不可删除", Layer.BUSINESS),
    MENU_IN_USE(10009, "菜单存在子节点或已被角色引用，不可删除", Layer.BUSINESS),
    FILE_TYPE_OR_SIZE_INVALID(10011, "文件类型不支持或大小超出限制", Layer.BUSINESS),
    OPERATION_ON_SELF_FORBIDDEN(10012, "不允许对当前登录账号执行该操作", Layer.BUSINESS),
    PHONE_ALREADY_USED(10013, "手机号已被占用", Layer.BUSINESS),
    OLD_PASSWORD_WRONG(10014, "原密码错误", Layer.BUSINESS),
    ACCOUNT_ARCHIVED(10015, "账号已归档，禁止登录", Layer.BUSINESS),
    CONFIG_KEY_NOT_ALLOWED(10016, "配置键不存在或不允许修改", Layer.BUSINESS),
    /**
     * ①本人所在节点 status=1，或②祖先链中存在 node_type=1 且 status=1 的管理员（分支冻结）。
     * 与 10005 是两个不同的原因，前端提示语不同，不得合并（契约 §2.3）。
     */
    NODE_OR_ANCESTOR_DISABLED(10017, "账号已被停用或其上级已停用", Layer.BUSINESS),

    // ------------------------ 组织树结构段 10101~10199 ------------------------
    NODE_NOT_FOUND(10101, "节点不存在或已删除", Layer.BUSINESS),
    NODE_NAME_DUPLICATED(10102, "同级下节点名称重复", Layer.BUSINESS),
    /** 目标父节点是被移动节点自身或其后代（契约 §2.3 约束 2，铁律 1） */
    NODE_MOVE_WOULD_CREATE_CYCLE(10103, "节点移动会导致成环", Layer.BUSINESS),
    NODE_PARENT_CHILD_TYPE_INVALID(10104, "非法的父子节点类型组合", Layer.BUSINESS),
    TEACHER_NODE_ONLY_ACCEPTS_STUDENT(10105, "教师节点下只能挂学生", Layer.BUSINESS),
    STUDENT_NODE_MUST_BE_LEAF(10106, "学生节点不可有子节点", Layer.BUSINESS),
    /**
     * <b>仅用于请求体/查询参数中显式指定的目标</b>越界（targetParentId、targetNodeIds、
     * studentIds、nodeId 等）。路径 {@code {id}} 上的资源越界返回 404，功能权限不足返回 403。
     */
    TARGET_NODE_OUT_OF_SCOPE(10107, "目标节点不在您的管辖范围内", Layer.BUSINESS),
    NODE_HAS_CHILDREN(10108, "节点下存在子节点，不可删除", Layer.BUSINESS),
    NODE_DISABLED(10109, "节点已停用，不可执行该操作", Layer.BUSINESS),

    // ------------------------ 人员与学籍段 10201~10299 ------------------------
    TEACHER_NO_ALREADY_EXISTS(10201, "教师工号已存在", Layer.BUSINESS),
    STUDENT_NO_ALREADY_EXISTS(10202, "学号已存在", Layer.BUSINESS),
    STUDENT_ARCHIVED_OR_QUIT(10203, "学生已归档/已退课，不可执行该操作", Layer.BUSINESS),
    STUDENT_NOT_ARCHIVED(10204, "学生未处于归档状态，不可恢复", Layer.BUSINESS),
    TARGET_PARENT_UNCHANGED(10205, "目标节点与当前上级相同", Layer.BUSINESS),
    TEACHER_STILL_HAS_STUDENTS(10206, "教师仍有名下学员，不可删除", Layer.BUSINESS),
    STUDENT_COUNT_EXCEEDS_LIMIT(10207, "学生数量超出机构可容纳上限", Layer.BUSINESS),
    BATCH_CONTAINS_INVALID_STATUS_STUDENT(10208, "退课/归档名单包含状态不符的学员", Layer.BUSINESS),
    /** 已过 30 日撤回窗口、org_student.anonymized_at 非空。参数合法但对象状态不允许，故用业务码而非 400 */
    STUDENT_ALREADY_ANONYMIZED(10209, "学员已脱敏，不可恢复", Layer.BUSINESS),

    // ------------------------ 资源授权段 10301~10399 ------------------------
    /** 授权人既非 owner_node_id，也未被授予该资源；不区分"不存在"与"无权"以防探测（契约 §2.5 规则 1） */
    RESOURCE_NOT_FOUND_OR_NO_GRANT_RIGHT(10301, "资源不存在或您无权授权该资源", Layer.BUSINESS),
    GRANT_TARGET_OUT_OF_SUBTREE(10302, "授权目标不在您的子树内", Layer.BUSINESS),
    GRANT_ALREADY_EXISTS(10303, "该资源已授权给目标节点", Layer.BUSINESS),
    /** 保留登记：资源使用侧的过期判定由 20013（课程）与题目可见性 404 承担，本码当前无接口抛出 */
    GRANT_EXPIRED(10304, "授权已过期或不在有效期内", Layer.BUSINESS),
    PERM_TEMPLATE_NOT_FOUND_OR_DISABLED(10305, "权限模板不存在或已停用", Layer.BUSINESS),
    /** 模板清单 ∩ 授权人当前拥有的资源 = 空集（契约 §2.5 模板取交集规则，绝不放大权限） */
    PERM_TEMPLATE_NO_GRANTABLE_RESOURCE(10306, "模板中无任何可授出的资源", Layer.BUSINESS),
    GRANT_RECORD_NOT_FOUND(10307, "授权记录不存在或已撤销", Layer.BUSINESS),
    /** resource_type 为 2（题目）或 3（视频）时目标为学生节点（契约 §2.5 规则 11） */
    RESOURCE_TYPE_NOT_GRANTABLE_TO_STUDENT(10308, "该资源类型不可授权给学生节点", Layer.BUSINESS),

    // ------------------------ 导入段 10401~10499 ------------------------
    IMPORT_TASK_IN_PROGRESS(10401, "存在处理中的学生导入任务", Layer.BUSINESS),
    IMPORT_ROW_COUNT_EXCEEDS_LIMIT(10402, "导入文件行数超出上限（2000 行）", Layer.BUSINESS),

    // ------------------------ 标签段 10501~10599 ------------------------
    TAG_NAME_ALREADY_EXISTS(10501, "标签名称已存在", Layer.BUSINESS),
    TAG_NOT_FOUND(10502, "标签不存在或已删除", Layer.BUSINESS),

    // ========================================================================
    // §9.3 业务错误码 2xxxx —— 课程与视频
    // （20011~20012、20021~20099 为预留号段；20007 已于模块 08 启用，见下）
    // ========================================================================
    PLAY_AUTH_INVALID(20001, "播放凭证过期或无效", Layer.BUSINESS),
    /** 间隔 <8s 或参数非法，本次不计入；≥8s 均有效、单次封顶计入 15s，超窗不补算不报错 */
    HEARTBEAT_INTERVAL_ABNORMAL(20002, "心跳间隔异常被丢弃", Layer.BUSINESS),
    VIDEO_TRANSCODE_NOT_FINISHED(20003, "视频转码未完成", Layer.BUSINESS),
    COURSE_NOT_FOUND(20004, "课程不存在或不可用", Layer.BUSINESS),
    COURSE_STATUS_NOT_ALLOWED(20005, "课程当前状态不允许该操作", Layer.BUSINESS),
    CHAPTER_LEVEL_INVALID(20006, "章节层级不合法", Layer.BUSINESS),
    /**
     * 模块 08 启用的预留号位（G 定案）。
     *
     * <p>03-03 §3.3 规则 1「{@code chapterId} 必须属于目标课程」<b>没有给错误码</b>，
     * 而该接口的错误码栏（{@code 20004} / {@code 20008} / {@code 20009} / {@code 20019}）
     * 一个都不匹配。<b>不把 {@code 20006} 扩义到「课时挂载点非法」</b> ——
     * 一致性检查 C3 校验的是「同码同义」且作用于全局，一处扩义会改变所有引用处的含义。
     * 故取 §9.3 明确登记的预留号段里的 {@code 20007}。已登记 <b>F-46</b>。
     */
    CHAPTER_NOT_FOUND_IN_COURSE(20007, "章节不存在或不属于该课程", Layer.BUSINESS),
    RELATED_VIDEO_UNAVAILABLE(20008, "关联视频不存在或状态不可用", Layer.BUSINESS),
    RELATED_MATERIAL_UNAVAILABLE(20009, "关联图文资料不存在或不可用", Layer.BUSINESS),
    MATERIAL_IN_USE(20010, "图文资料已被课时引用，不可删除", Layer.BUSINESS),
    /** 契约 §2.5 规则 4：只判本节点一条命中，不回溯祖先链 */
    COURSE_NOT_GRANTED(20013, "未被授权访问该课程或授权已过期", Layer.BUSINESS),
    LESSON_NOT_FOUND_OR_INVISIBLE(20014, "课时不存在或不可见", Layer.BUSINESS),
    VIDEO_NOT_FOUND_OR_STATUS_INVALID(20015, "媒资不存在或状态不允许该操作", Layer.BUSINESS),
    VIDEO_IN_USE(20016, "媒资已被课时引用，不可删除", Layer.BUSINESS),
    /**
     * <b>已废弃，不得复用本号位。</b>转码事件已改为 XXL-Job 拉取轻量消息队列消费
     * （03-课程与视频 §7.2），消费凭 AK/SK 鉴权，无签名校验环节。
     * 保留登记的理由与 §9.3 原文一致：<b>复用会让历史日志里的 20017 产生歧义。</b>
     */
    @Deprecated
    CALLBACK_SIGNATURE_INVALID(20017, "回调签名校验失败（已废弃）", Layer.BUSINESS),
    CHAPTER_SORT_MISMATCH(20018, "章节排序参数与课程实际章节不一致", Layer.BUSINESS),
    LESSON_TYPE_RESOURCE_MISMATCH(20019, "课时类型与关联资源参数不匹配", Layer.BUSINESS),
    /** 同课时会话择一（03-03 §8.3.1 规则 9）。前端不得静默降级，须暂停播放并明确提示 */
    LESSON_PLAYING_ON_ANOTHER_DEVICE(20020, "该课时正在其他设备播放", Layer.BUSINESS),

    // ========================================================================
    // §9.4 业务错误码 3xxxx —— 题库与作业（30018~30099 为预留号段）
    // ========================================================================
    QUESTION_IN_USE_CANNOT_DISABLE(30001, "题目已被作业引用，不可停用", Layer.BUSINESS),
    HOMEWORK_DEADLINE_PASSED(30002, "作业已截止", Layer.BUSINESS),
    ANSWER_SHEET_ALREADY_SUBMITTED(30003, "答卷已提交，不可重复提交", Layer.BUSINESS),
    QUESTION_CATEGORY_NOT_EMPTY(30004, "分类下存在子分类或题目，不可删除", Layer.BUSINESS),
    QUESTION_IN_USE_CANNOT_REMOVE(30005, "题目已被作业引用，不可删除", Layer.BUSINESS),
    QUESTION_CONTENT_TYPE_MISMATCH(30006, "题目内容与题型不匹配", Layer.BUSINESS),
    QUESTION_VERSION_NOT_FOUND(30007, "题目版本不存在", Layer.BUSINESS),
    QUESTION_NOT_ENABLED(30008, "题目未启用，不可加入或发布作业", Layer.BUSINESS),
    HOMEWORK_STATUS_NOT_ALLOWED(30009, "作业当前状态不允许该操作", Layer.BUSINESS),
    HOMEWORK_STATUS_NOT_PUBLISHABLE(30010, "作业当前状态不可发布", Layer.BUSINESS),
    /** studentIds 为空，或分发名单中存在不在子树内的学生。整批拒绝，响应 data.invalidStudentIds 列出越界学员 */
    HOMEWORK_TARGET_EMPTY_OR_OUT_OF_SCOPE(30011, "发布目标为空或超出数据权限范围", Layer.BUSINESS),
    HOMEWORK_STATUS_NOT_REVOCABLE(30012, "作业当前状态不可撤回", Layer.BUSINESS),
    HOMEWORK_NOT_PUBLISHED(30013, "作业未发布或已撤回，不可作答", Layer.BUSINESS),
    ANSWER_SHEET_STATUS_NOT_GRADABLE(30014, "答卷当前状态不允许批改", Layer.BUSINESS),
    GRADE_SCORE_OUT_OF_RANGE(30015, "打分超出该题分值范围", Layer.BUSINESS),
    ANSWER_SHEET_SUBMITTED_CANNOT_EDIT(30016, "答卷已提交，不可修改作答内容", Layer.BUSINESS),
    /** 已批改答卷仅允许在首次批改完成（grade_time）后 7 天内修正打分（PRD F3-7 规则 5） */
    GRADE_CORRECTION_WINDOW_CLOSED(30017, "批改修正已超期", Layer.BUSINESS),

    // ========================================================================
    // §9.5 业务错误码 4xxxx —— 数据中心（40009~40099 为预留号段）
    // ========================================================================
    EXPORT_TASK_FAILED(40001, "导出任务生成失败", Layer.BUSINESS),
    EXPORT_PARAM_INVALID(40002, "导出参数错误", Layer.BUSINESS),
    EXPORT_TASK_NOT_FOUND(40003, "导出任务不存在或非本人创建", Layer.BUSINESS),
    EXPORT_TASK_DUPLICATED(40004, "相同参数的导出任务正在排队或生成中", Layer.BUSINESS),
    HOMEWORK_NOT_PUBLISHED_NO_STAT(40005, "作业未发布，暂无统计数据", Layer.BUSINESS),
    STAT_DATE_RANGE_INVALID(40006, "统计日期范围不合法", Layer.BUSINESS),
    EXPORT_ROW_COUNT_EXCEEDS_LIMIT(40007, "导出数据量超过上限", Layer.BUSINESS),
    EXPORT_FILE_EXPIRED(40008, "报表文件已过期清理", Layer.BUSINESS);

    /**
     * 错误码所属层次，决定 HTTP 状态码怎么给（00-通用约定 §3.3）。
     */
    public enum Layer {
        /** 框架层：HTTP 状态码 == 响应体 code */
        FRAMEWORK,
        /** 业务层：HTTP 状态码统一 200，以响应体 code 判定 */
        BUSINESS
    }

    private final int code;
    private final String msg;
    private final Layer layer;

    ErrorCode(int code, String msg, Layer layer) {
        this.code = code;
        this.msg = msg;
        this.layer = layer;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public Layer getLayer() {
        return layer;
    }

    /** 业务错误统一 HTTP 200，框架层错误 HTTP 码与 code 一致（00-通用约定 §3.3）。 */
    public int httpStatus() {
        return layer == Layer.BUSINESS ? SUCCESS.code : code;
    }

    // ------------------------------------------------------------------
    // 按 code 反查。启动即建，重复 code 直接抛异常 —— 同一个 code 全局
    // 只允许一个语义（§9 导语），重复登记是缺陷，不该等到运行时才发现。
    // ------------------------------------------------------------------
    private static final Map<Integer, ErrorCode> BY_CODE;

    static {
        Map<Integer, ErrorCode> map = new HashMap<>();
        for (ErrorCode e : values()) {
            ErrorCode prev = map.put(e.code, e);
            if (prev != null) {
                throw new IllegalStateException(
                        "错误码重复登记：" + e.code + " 同时是 " + prev.name() + " 与 " + e.name()
                                + "。同一个 code 全局仅允许一个语义（00-通用约定 §9）");
            }
        }
        BY_CODE = Collections.unmodifiableMap(map);
    }

    public static ErrorCode of(int code) {
        return BY_CODE.get(code);
    }

    public static Map<Integer, ErrorCode> registry() {
        return BY_CODE;
    }
}
