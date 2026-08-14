package com.edumatrix.org.node.vo;

/**
 * 重置人员密码的响应（03-02 §3.6）。
 */
public class NodePasswordResetVO {

    private Long nodeId;

    private Long userId;

    private String realName;

    /**
     * 新密码明文，<b>仅本次响应返回一次</b>，供管理员转告本人；
     * 数据库仅存 BCrypt 密文，<b>不可再查</b>（PRD §7.3 安全条款 1：明文永不落库）。
     */
    private String newPassword;

    /** 恒为 {@code true}（{@code pwd_reset_flag = 1}），本人下次登录必须先改密。 */
    private boolean mustChangeOnNextLogin;

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public boolean isMustChangeOnNextLogin() {
        return mustChangeOnNextLogin;
    }

    public void setMustChangeOnNextLogin(boolean mustChangeOnNextLogin) {
        this.mustChangeOnNextLogin = mustChangeOnNextLogin;
    }
}
