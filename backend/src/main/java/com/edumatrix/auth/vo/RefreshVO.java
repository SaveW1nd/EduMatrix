package com.edumatrix.auth.vo;

/**
 * 刷新令牌响应（03-01 §1.3 五个字段）。
 *
 * <p>比 {@link LoginVO} 少 {@code userId} / {@code userType} / {@code needChangePassword}
 * 三项 —— §1.3 的响应示例里就没有它们。刷新是续签，不是重新登录，
 * 客户端此时已经持有这些信息。
 */
public record RefreshVO(String tokenType,
                        String accessToken,
                        long expiresIn,
                        String refreshToken,
                        long refreshExpiresIn) {
}
