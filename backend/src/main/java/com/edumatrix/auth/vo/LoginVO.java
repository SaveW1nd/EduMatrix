package com.edumatrix.auth.vo;

/**
 * 登录响应（03-01 §1.2 八个字段，逐字对齐）。
 *
 * @param userId             {@code Long} → 由 {@code common/id} 的全局序列化器输出为字符串
 *                           （00-通用约定 §5，防 JS 精度丢失）
 * @param userType           0 平台超管 1 管理员 2 教师 3 学生（契约 §5）
 * @param needChangePassword {@code pwd_reset_flag = 1} 时为 true；前端强制跳转改密页，
 *                           未改密前不得进入其他页面（PRD F1-1 / F1-4 验收）
 */
public record LoginVO(String tokenType,
                      String accessToken,
                      long expiresIn,
                      String refreshToken,
                      long refreshExpiresIn,
                      Long userId,
                      Integer userType,
                      boolean needChangePassword) {
}
