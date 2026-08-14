package com.edumatrix.system.tenant.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;

/**
 * 租户续期请求（03-01 §5.6）。
 *
 * <p>新的到期时间<b>须晚于当前时间</b>（早于返回 400），但<b>允许早于原到期时间</b>
 * ——§5.6 逐字：「早于用于纠错回调」。所以这里不做"必须比原值晚"的判定，
 * 那会把纠错这条正当路径堵死。
 */
public class TenantRenewReq {

    @NotNull(message = "不能为空")
    private LocalDateTime expireTime;

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }
}
