package com.edumatrix.common.account;

import java.util.Collection;
import java.util.Map;

/**
 * 批量取用户真实姓名 —— 接口在 {@code common/}，实现在 {@code system/user/}。
 *
 * <p>03-03 §1.2 的 {@code createByName}（作者署名）与 §4.1 的 {@code createByName} 要它。
 * 契约 §2.2 定死了署名一律用公共字段 {@code create_by}（指向 {@code sys_user.id}），
 * 而 {@code course} 领域不能 import {@code system}（约定检查③）。
 *
 * <p>与 {@code common/account/PasswordHasher} + {@code SessionRevoker} 同型：
 * 能力声明在 {@code common/}，实现在提供方领域内。
 */
public interface UserNameReader {

    /**
     * @return {@code userId → real_name}；查不到的 id 直接缺席
     */
    Map<Long, String> realNames(Collection<Long> userIds);
}
