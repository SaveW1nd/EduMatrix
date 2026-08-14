package com.edumatrix.system.tenant.vo;

import java.time.LocalDateTime;

/**
 * 续期响应（03-01 §5.6）。<b>响应 msg 是「续期成功」</b>而不是通用的「操作成功」——
 * §5.6 的响应示例逐字如此。
 *
 * <p>续期后若租户此前因到期被拦截，<b>登录即时恢复</b>：判定读的是
 * {@code sys_tenant.expire_time} 这一行（{@code LoginCheckService} 每次登录都查），
 * 没有任何缓存需要失效。
 */
public class TenantRenewedVO {

    private Long id;
    private String name;
    private LocalDateTime expireTime;
    private Integer status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
