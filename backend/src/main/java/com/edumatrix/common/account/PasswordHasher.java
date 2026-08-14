package com.edumatrix.common.account;

/**
 * 口令哈希的能力。SPI：本接口在 {@code common/}，实现在 {@code auth/}（委派给
 * {@code auth/service/PasswordService}）。
 *
 * <h2>为什么不让 {@code system} 自己 new 一个 BCryptPasswordEncoder</h2>
 * <p>因为 <b>cost 是一个必须全库同值的参数</b>。PRD §7.3 安全条款 1 要求 cost ≥ 10，
 * {@code PasswordService} 取的是下限 10 并写明了理由（BCrypt 耗时随 cost 指数增长，
 * 而它跑在登录这条同步路径上）。若 {@code system} 与 {@code org} 各自 {@code new} 一个，
 * 将来把 {@code PasswordService.BCRYPT_COST} 调到 12 时，<b>只有登录校验用新值，
 * 新建账号仍然写 cost 10 的密文</b> —— 而 BCrypt 把 cost 编码在 hash 里（{@code $2a$10$...}），
 * 所以两边都能验通过，<b>不报错、不失败，只是安全强度悄悄回退</b>。
 *
 * <p>与 {@link SessionRevoker} 同理：消费方跨领域（{@code system} 的 03-01 §2.2 建用户 /
 * §2.5 重置密码，{@code org} 的 03-02 §3.6 / §4.2 / §5.2 / §6.2），
 * 而检查③禁止它们 import {@code auth}。走 common 声明、auth 实现这条既有路径。
 *
 * <h2>只有哈希，没有校验，也没有强度校验</h2>
 * <p>本接口<b>刻意只暴露 {@link #hash}</b>：
 * <ul>
 *   <li><b>校验（{@code matches}）不给</b> —— 校验明文口令是登录链路的事，
 *       在 {@code auth} 内部完成。给出去就等于邀请别的领域自建一条登录旁路；
 *   <li><b>强度校验不给</b> —— {@code PasswordService.assertStrongEnough} 的规则里含
 *       「不得与原密码相同」，那是 03-01 §1.6 <b>本人改密</b>的语义；而 §2.5 管理员重置
 *       没有这一条（管理员不知道对方原密码，也不该知道）。两处规则不同，
 *       共用一个方法迟早会把 §1.6 的规则漏进 §2.5。§2.5 的格式校验由它自己的 DTO 注解承担。
 * </ul>
 */
public interface PasswordHasher {

    /**
     * 生成口令密文（BCrypt，cost 见 {@code PasswordService.BCRYPT_COST}）。
     *
     * <p><b>明文永不落库</b>（PRD §7.3 安全条款 1）。调用方拿到密文后即应丢弃明文引用，
     * 除非该接口约定「明文在本次响应中返回一次」（03-01 §2.5 / 03-02 §4.2 的 {@code initPassword}）。
     */
    String hash(String rawPassword);
}
