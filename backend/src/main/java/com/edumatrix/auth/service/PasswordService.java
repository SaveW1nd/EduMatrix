package com.edumatrix.auth.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;

/**
 * 口令：BCrypt 存储 + 弱密码策略（PRD §7.3 安全条款 1）。
 *
 * <h2>cost 取 10</h2>
 * <p>PRD 的要求是「cost ≥ 10」。取下限而不是更高值：BCrypt 的耗时随 cost 指数增长，
 * 而它跑在<b>登录这条同步路径</b>上 —— cost 12 是 cost 10 的 4 倍耗时，
 * 在登录高峰会直接转化为线程池占用。要提高时改这一个常量即可，
 * 已有密文因为 cost 写在 hash 里（{@code $2a$10$...}）而<b>仍然可验</b>，无需批量重算。
 *
 * <h2>密码错误一律不区分原因</h2>
 * <p>账号不存在与密码错误都返回 {@code 10003}（00-通用约定 §9.2：
 * 「登录失败（不区分具体哪项错误，防撞库探测）」）。区分开就等于提供了一个
 * 账号存在性探测接口。
 */
@Service
public class PasswordService {

    /** PRD §7.3：BCrypt cost ≥ 10。 */
    public static final int BCRYPT_COST = 10;

    /** PRD §7.3 弱密码策略 / 03-01 §1.6：8~20 位且同时含字母与数字。 */
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 20;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);

    /** 明文与密文是否匹配。密文非法（如占位符）时返回 false 而不是抛异常。 */
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException e) {
            // 基线示例数据里的 $2a$10$BCRYPT_PLACEHOLDER_... 不是合法 BCrypt 密文，
            // 按「密码不匹配」处理，不让它变成 500
            return false;
        }
    }

    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 新密码的格式校验（03-01 §1.6：不合规或与原密码相同返回 <b>400</b>，不是业务码）。
     *
     * @param oldPassword 原密码明文，用于「不得与原密码相同」的判定
     */
    public void assertStrongEnough(String newPassword, String oldPassword) {
        if (newPassword == null || newPassword.length() < MIN_LENGTH
                || newPassword.length() > MAX_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "newPassword 长度须为 " + MIN_LENGTH + "~" + MAX_LENGTH + " 位");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : newPassword.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "newPassword 须同时包含字母与数字");
        }
        if (newPassword.equals(oldPassword)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "newPassword 不得与原密码相同");
        }
    }
}
