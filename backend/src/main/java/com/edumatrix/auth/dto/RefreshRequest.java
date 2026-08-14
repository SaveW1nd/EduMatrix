package com.edumatrix.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 刷新令牌请求（03-01 §1.3）。 */
public class RefreshRequest {

    /** 登录或上次刷新时下发的刷新令牌；每次刷新后旧的立即失效（00-通用约定 §2.2 规则 3）。 */
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
