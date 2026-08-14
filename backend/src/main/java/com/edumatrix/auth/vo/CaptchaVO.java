package com.edumatrix.auth.vo;

/**
 * 图形验证码响应（03-01 §1.1 三个字段，逐字对齐）。
 *
 * @param captchaKey    验证码唯一标识（即 Redis key），登录时原样带回
 * @param captchaImage  Base64 图片（Data URI），前端直接作为 {@code <img src>}
 * @param expireSeconds 有效期 300 秒，过期后需重新获取
 */
public record CaptchaVO(String captchaKey, String captchaImage, int expireSeconds) {
}
