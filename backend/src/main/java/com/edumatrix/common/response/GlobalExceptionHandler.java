package com.edumatrix.common.response;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.metrics.MetricsRegistry;
import com.edumatrix.common.tenant.TenantContextMissingException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.common.trace.TraceIdHolder;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理器：把一切异常收敛成统一响应体 {@link R}（00-通用约定 §3 / §3.3）。
 *
 * <h2>HTTP 状态码怎么给</h2>
 * <ul>
 *   <li>框架层错误（400/401/403/404/405/429/500）：HTTP 状态码与 {@code code} <b>一致</b>；
 *   <li>业务错误（1xxxx~4xxxx）：HTTP 一律 <b>200</b>，由 {@code code} 判定。
 * </ul>
 * 具体由 {@link ErrorCode#httpStatus()} 决定，本类不重复判断。
 *
 * <h2>日志分级（契约 §7.1）</h2>
 * <ul>
 *   <li><b>越权拒绝（403 / 404 / 10107）一律 WARN</b>，且必须带
 *       {@code traceId + userId + 目标对象 ID} —— 少了目标对象 ID，一条越权探测记录
 *       就只能告诉你"有人被拒了"，没法告诉你"他在探什么"；
 *   <li>参数校验失败等可预期拒绝：INFO / WARN，不打堆栈（它们不是缺陷）；
 *   <li>未预期异常：ERROR + 堆栈。
 * </ul>
 *
 * <h2>指标</h2>
 * <p>三分法的三个出口都从这里过，所以 {@link MetricsRegistry#API_PERMISSION_DENIED_TOTAL}
 * （标签 {@code code}）就在这里打点。契约 §7.1 的告警线是单账号 5min 内 &gt; 100 ——
 * <b>403/404/10107 突增 = 越权探测</b>。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ==================================================================== 业务

    @ExceptionHandler(BizException.class)
    public R<Object> handleBiz(BizException ex, HttpServletResponse response, WebRequest request) {
        ErrorCode code = ex.getErrorCode();
        response.setStatus(code.httpStatus());

        if (ex.isPermissionDenial()) {
            countPermissionDenied(code);
            // 契约 §7.1：越权拒绝一律 WARN，带 traceId + userId + 目标对象 ID
            log.warn("越权拒绝 code={} traceId={} userId={} targetId={} path={}",
                    code.getCode(), TraceIdHolder.get(), TenantHelper.getUserId(),
                    ex.getTargetId(), describe(request));
        } else {
            log.info("业务拒绝 code={} traceId={} msg={} path={}",
                    code.getCode(), TraceIdHolder.get(), ex.getMessage(), describe(request));
        }
        return ex.getData() == null
                ? R.fail(code, ex.getMessage())
                : R.fail(code, ex.getData());
    }

    // ============================================================ Sa-Token 鉴权

    @ExceptionHandler(NotLoginException.class)
    public R<Object> handleNotLogin(NotLoginException ex, HttpServletResponse response) {
        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus());
        log.info("未登录 traceId={} type={}", TraceIdHolder.get(), ex.getType());
        return R.fail(ErrorCode.UNAUTHORIZED);
    }

    /**
     * {@code @SaCheckPermission} / {@code @SaCheckRole} 未通过 → <b>403</b>。
     *
     * <p>这是三分法里的「我没资格做这件事」：与数据无关，是功能级拒绝，
     * 不能返回 404（那会让前端把"没权限"当成"数据不存在"）。
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public R<Object> handleNoPermission(RuntimeException ex, HttpServletResponse response, WebRequest request) {
        response.setStatus(ErrorCode.FORBIDDEN.httpStatus());
        countPermissionDenied(ErrorCode.FORBIDDEN);
        log.warn("越权拒绝 code=403 traceId={} userId={} detail={} path={}",
                TraceIdHolder.get(), TenantHelper.getUserId(), ex.getMessage(), describe(request));
        return R.fail(ErrorCode.FORBIDDEN);
    }

    // ============================================================ 参数校验 400

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Object> handleInvalidArgument(MethodArgumentNotValidException ex, HttpServletResponse response) {
        response.setStatus(ErrorCode.BAD_REQUEST.httpStatus());
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("；"));
        return badRequest(detail);
    }

    @ExceptionHandler(BindException.class)
    public R<Object> handleBind(BindException ex, HttpServletResponse response) {
        response.setStatus(ErrorCode.BAD_REQUEST.httpStatus());
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("；"));
        return badRequest(detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public R<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletResponse response) {
        response.setStatus(ErrorCode.BAD_REQUEST.httpStatus());
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("；"));
        return badRequest(detail);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Object> handleMissingParam(MissingServletRequestParameterException ex, HttpServletResponse response) {
        response.setStatus(ErrorCode.BAD_REQUEST.httpStatus());
        return badRequest("缺少必填参数：" + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletResponse response) {
        response.setStatus(ErrorCode.BAD_REQUEST.httpStatus());
        return badRequest("参数类型不正确：" + ex.getName());
    }

    /**
     * 请求体无法解析。
     *
     * <p>典型场景之一正是契约 §5 那条<b>单独强调</b>的：判断题的答案必须是 JSON 布尔
     * {@code true}，不是字符串 {@code "true"}。服务端反序列化时做类型校验、收到
     * {@code "true"} 直接 400、<b>不做隐式转换</b> —— 因为两者在 JSON 里都"看得过去"，
     * 而 {@code true != "true"} 会让全部判断题一律判错，且客观题不开放教师改分，
     * 错了没有救济路径。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Object> handleUnreadable(HttpMessageNotReadableException ex, HttpServletResponse response) {
        response.setStatus(ErrorCode.BAD_REQUEST.httpStatus());
        log.info("请求体解析失败 traceId={} msg={}", TraceIdHolder.get(), ex.getMessage());
        return R.fail(ErrorCode.BAD_REQUEST, "请求体格式错误");
    }

    // ================================================================ 404 / 405

    @ExceptionHandler(NoResourceFoundException.class)
    public R<Object> handleNoResource(NoResourceFoundException ex, HttpServletResponse response) {
        response.setStatus(ErrorCode.NOT_FOUND.httpStatus());
        return R.fail(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                              HttpServletResponse response) {
        response.setStatus(ErrorCode.METHOD_NOT_ALLOWED.httpStatus());
        return R.fail(ErrorCode.METHOD_NOT_ALLOWED);
    }

    // =========================================================== 租户上下文缺失

    /**
     * 租户上下文四条路径全落空 → 500。
     *
     * <p>它是<b>缺陷</b>而不是业务拒绝：某个入口漏了 {@code runWithTenant}。
     * 契约 §2.8 规则 3 要求此时「一律拒绝并告警」，所以这里是 ERROR 而不是 WARN ——
     * 退化成一条没有租户条件的全库查询，比让这次请求失败严重得多。
     */
    @ExceptionHandler(TenantContextMissingException.class)
    public R<Object> handleTenantMissing(TenantContextMissingException ex, HttpServletResponse response,
                                         WebRequest request) {
        response.setStatus(ErrorCode.INTERNAL_ERROR.httpStatus());
        log.error("租户上下文缺失 traceId={} userId={} path={}",
                TraceIdHolder.get(), TenantHelper.getUserId(), describe(request), ex);
        return R.fail(ErrorCode.INTERNAL_ERROR);
    }

    // ==================================================================== 兜底

    @ExceptionHandler(Exception.class)
    public R<Object> handleOthers(Exception ex, HttpServletResponse response, WebRequest request) {
        response.setStatus(ErrorCode.INTERNAL_ERROR.httpStatus());
        log.error("未预期异常 traceId={} userId={} path={}",
                TraceIdHolder.get(), TenantHelper.getUserId(), describe(request), ex);
        return R.fail(ErrorCode.INTERNAL_ERROR);
    }

    // ================================================================== 内部方法

    private R<Object> badRequest(String detail) {
        log.info("参数校验失败 traceId={} detail={}", TraceIdHolder.get(), detail);
        return R.fail(ErrorCode.BAD_REQUEST,
                detail == null || detail.isEmpty() ? ErrorCode.BAD_REQUEST.getMsg() : detail);
    }

    private void countPermissionDenied(ErrorCode code) {
        meterRegistry.counter(MetricsRegistry.API_PERMISSION_DENIED_TOTAL,
                MetricsRegistry.TAG_CODE, String.valueOf(code.getCode())).increment();
    }

    private static String formatFieldError(FieldError e) {
        return e.getField() + " " + e.getDefaultMessage();
    }

    private static String describe(WebRequest request) {
        return request == null ? "-" : request.getDescription(false);
    }
}
