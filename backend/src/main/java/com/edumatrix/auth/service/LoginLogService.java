package com.edumatrix.auth.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.edumatrix.auth.entity.AuthLoginLog;
import com.edumatrix.auth.mapper.AuthLoginLogMapper;
import com.edumatrix.common.tenant.TenantHelper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 登录日志 —— <b>成功与失败都写</b>（PRD F1-1 验收标准明确要求失败也留痕）。
 *
 * <h2>为什么失败也要写，且不能让它影响登录结果</h2>
 * <p>失败留痕是撞库排查的唯一原始事实：没有它，「某账号被人试了 500 次密码」在系统里
 * 不留任何痕迹。但反过来，<b>写日志失败不能把一次本该成功的登录变成 500</b> ——
 * 所以这里吞掉异常并记 ERROR。日志是证据，不是流程的一环。
 *
 * <h2>租户上下文</h2>
 * <p>{@code sys_login_log} 有 {@code tenant_id} 列，租户插件会在 INSERT 时注入。
 * 但登录失败且<b>账号不存在</b>时，那一刻根本不知道租户是谁 —— 四条取值路径全落空，
 * {@code requireTenantId()} 会抛异常。故这里一律用
 * {@code TenantHelper.runWithTenant(...)} 显式提供：知道就用真值，不知道就用
 * {@code 0}（DDL 里该列的默认值本就是 0）。
 */
@Service
public class LoginLogService {

    private static final Logger log = LoggerFactory.getLogger(LoginLogService.class);

    /** 平台级/未知租户的日志行（DDL 默认值口径）。 */
    private static final long UNKNOWN_TENANT_ID = 0L;

    /** {@code sys_login_log.msg} 是 {@code VARCHAR(255)}，超长会让整条 INSERT 失败。 */
    private static final int MAX_MSG_LENGTH = 255;

    private final AuthLoginLogMapper loginLogMapper;

    public LoginLogService(AuthLoginLogMapper loginLogMapper) {
        this.loginLogMapper = loginLogMapper;
    }

    /** 登录成功。 */
    public void recordSuccess(Long userId, String username, Long tenantId) {
        record(userId, username, tenantId, AuthLoginLog.STATUS_SUCCESS, "登录成功");
    }

    /**
     * 登录失败。
     *
     * @param userId   账号不存在时为 {@code null}（DDL 注释逐字如此）
     * @param tenantId 未知时传 {@code null}，落库为 0
     * @param reason   失败原因，会被截断到 255 字符
     */
    public void recordFailure(Long userId, String username, Long tenantId, String reason) {
        record(userId, username, tenantId, AuthLoginLog.STATUS_FAIL, reason);
    }

    private void record(Long userId, String username, Long tenantId, int status, String msg) {
        try {
            AuthLoginLog entity = new AuthLoginLog();
            entity.setUserId(userId);
            entity.setUsername(truncate(username, 50));
            entity.setIp(currentIp());
            entity.setUserAgent(truncate(currentUserAgent(), 500));
            entity.setStatus(status);
            entity.setMsg(truncate(msg, MAX_MSG_LENGTH));
            entity.setLoginTime(LocalDateTime.now());
            TenantHelper.runWithTenant(tenantId == null ? UNKNOWN_TENANT_ID : tenantId,
                    () -> loginLogMapper.insert(entity));
        } catch (RuntimeException e) {
            // 日志是证据，不是流程的一环：写不进去也不能改变登录结果
            log.error("写 sys_login_log 失败 username={} status={}", username, status, e);
        }
    }

    /**
     * 取客户端 IP。
     *
     * <p>优先 {@code X-Forwarded-For} 的第一段 —— 部署形态是 Nginx 反代（deploy/），
     * 不取的话所有登录日志的 IP 都会是网关地址，撞库排查直接失去意义。
     */
    public static String currentIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return truncate(first, 64);
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return truncate(realIp.trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private static String currentUserAgent() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getHeader("User-Agent");
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
