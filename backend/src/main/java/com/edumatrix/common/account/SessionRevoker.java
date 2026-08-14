package com.edumatrix.common.account;

/**
 * 作废某账号<b>全部</b>在线会话的能力。SPI：本接口在 {@code common/}，实现在 {@code auth/}。
 *
 * <h2>为什么是 SPI 而不是把实现搬进 common</h2>
 * <p>它有<b>两个不同领域</b>的消费方，这正是 {@code common/frozen/FrozenNodeCache}
 * 当初进 {@code common/} 的判据：
 * <ul>
 *   <li><b>{@code system}</b>（模块 03）：03-01 §2.4 删除用户「作废该用户在线 Token」、
 *       §2.5 重置密码「重置后该用户全部在线会话强制下线」、§2.6 停用「停用后该用户在线
 *       Token 立即作废」；
 *   <li><b>{@code org}</b>（模块 07）：03-02 §3.6 重置人员密码、§4.4 删除管理员、
 *       §5.4 删除教师、§6.4 删除学生，四处逐字写着「作废（该用户）在线 Token」。
 * </ul>
 * 而 05-工程结构.md §A1 的第三条硬约束禁止领域包互相 import
 * （{@code scripts/check_backend_conventions.sh} 检查③），
 * {@code system} / {@code org} 都够不着 {@code auth} 的 {@code TokenService}。
 *
 * <p><b>但实现不能搬进 {@code common/}</b>：作废一个账号的全部会话 =
 * Sa-Token 侧逐个 {@code logout} + refreshToken 侧按 {@code auth:refresh:uid:{userId}}
 * 索引集合逐个删，后半段依赖 refreshToken 的 <b>key 布局、SHA-256 哈希、索引集合结构</b>——
 * 那是 {@code TokenService} 的私有知识，而它刚刚为「删 key + SREM」这对动作抽出
 * {@code forget()}，理由逐字是「<b>会静默出错的动作，出现次数越少越好</b>」。
 * 复制一份进 {@code common/} 等于当场违反那句话。
 *
 * <p>所以走 {@code common} 声明接口、{@code auth} 实现的路 —— 这是
 * {@code common/tenant/CurrentContextProvider}（同样是 common 声明、模块 02 实现）
 * 的既有先例，不是新发明。
 *
 * <h2>与 {@code TokenService#revokeOtherSessions} 的区别</h2>
 * <p>那个<b>保留当前会话</b>（本人改密后自己不必重登）；本接口<b>不保留</b>，
 * 含调用者自己那一个。因为本接口的作用对象永远是<b>别人</b>——
 * 03-01 §2.3/§2.4/§2.6 都有 {@code 10012} 挡住「对当前登录账号动手」，
 * 而 §2.5 的原文就是「该用户<b>全部</b>在线会话强制下线」。
 */
public interface SessionRevoker {

    /**
     * 作废该账号的全部在线会话：accessToken 与 refreshToken 两侧都清。
     *
     * <p><b>不在事务里调用它也不会出错，但顺序有讲究</b>：与冻结集那两条顺序约束同向 ——
     * 宁可多作废一瞬，不可漏放一瞬。删除/停用/重置密码都应当<b>先提交事务再调用</b>
     * 或<b>在事务内调用</b>皆可（Redis 不参与回滚），但绝不可「先返回成功、再异步作废」。
     *
     * @param userId 目标账号；{@code null} 时静默返回（调用方通常刚查过该行，不必再判一次）
     */
    void revokeAllSessions(Long userId);
}
