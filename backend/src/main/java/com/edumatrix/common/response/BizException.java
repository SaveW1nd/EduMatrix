package com.edumatrix.common.response;

import com.edumatrix.common.errorcode.ErrorCode;

/**
 * 业务异常。抛出后由 {@link GlobalExceptionHandler} 统一转成 {@link R}。
 *
 * <p><b>为什么放在 {@code common/response} 而不是新建 {@code common/exception}</b>：
 * 05-工程结构.md §C2 把 {@code common/} 的子包逐个列死了（{@code response / errorcode /
 * tenant / subtree / id / trace / idempotent / operlog / metrics / config}），
 * 其中 {@code response/} 的内容写明是「R&lt;T&gt;、PageResult&lt;T&gt;、GlobalExceptionHandler」。
 * 本类是异常处理器的输入，与它同包；<b>不新增子包</b>，包结构不是实现方能改的。
 *
 * <p><b>越界拒绝用哪个（三分法，契约 §2.4 / 00-通用约定 §2.4）</b>：
 * <ul>
 *   <li>{@link #notFound()} —— 「我要操作的东西」越界：路径上的 {@code /xxx/{id}} 不在我的子树内，
 *       或跨租户。返回 404，<b>不暴露存在性</b>；
 *   <li>{@link #targetOutOfScope()} —— 「我选的目标」越界：请求体/参数里显式指定的
 *       {@code targetParentId} / {@code targetNodeIds} / {@code studentIds} 等。
 *       返回 {@code 10107}（HTTP 200），因为用户是主动选的，需要明确提示"请重新选择"而非静默 404；
 *   <li>{@link #forbidden()} —— 「我没资格做这件事」：角色/菜单权限不足，与数据无关。返回 403。
 * </ul>
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** 越权拒绝日志里要带的目标对象 ID（契约 §7.1 日志分级要求）。可为 null。 */
    private final transient Object targetId;

    /** 随失败响应一起返回的业务数据，如 {@code invalidStudentIds}。可为 null。 */
    private final transient Object data;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMsg(), null, null);
    }

    public BizException(ErrorCode errorCode, String msg) {
        this(errorCode, msg, null, null);
    }

    public BizException(ErrorCode errorCode, String msg, Object targetId, Object data) {
        super(msg);
        this.errorCode = errorCode;
        this.targetId = targetId;
        this.data = data;
    }

    // ---------------------------------------------------------------- 三分法

    /** 路径上的资源越界 / 不存在 / 跨租户 → 404，不暴露存在性。 */
    public static BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND);
    }

    /** 同上，带上目标 ID 供越权拒绝日志记录（不进响应体）。 */
    public static BizException notFound(Object targetId) {
        return new BizException(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMsg(), targetId, null);
    }

    /** 请求体/参数中显式指定的目标越界 → 10107（HTTP 200）。 */
    public static BizException targetOutOfScope() {
        return new BizException(ErrorCode.TARGET_NODE_OUT_OF_SCOPE);
    }

    /** 同上，并把越界的目标一并回给前端，让用户知道该重新选哪个。 */
    public static BizException targetOutOfScope(Object targetId, Object data) {
        return new BizException(ErrorCode.TARGET_NODE_OUT_OF_SCOPE,
                ErrorCode.TARGET_NODE_OUT_OF_SCOPE.getMsg(), targetId, data);
    }

    /** 功能权限不足 → 403，与数据无关。 */
    public static BizException forbidden() {
        return new BizException(ErrorCode.FORBIDDEN);
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String msg) {
        return new BizException(errorCode, msg);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getTargetId() {
        return targetId;
    }

    public Object getData() {
        return data;
    }

    /** 是否属于越权拒绝三分法（403 / 404 / 10107）—— 决定日志级别与指标打点。 */
    public boolean isPermissionDenial() {
        return errorCode == ErrorCode.FORBIDDEN
                || errorCode == ErrorCode.NOT_FOUND
                || errorCode == ErrorCode.TARGET_NODE_OUT_OF_SCOPE;
    }

    /** 业务异常不需要堆栈：它表达的是可预期的拒绝，不是缺陷，填栈只是白白拖慢高频拒绝路径。 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
