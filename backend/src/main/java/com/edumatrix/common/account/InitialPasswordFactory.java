package com.edumatrix.common.account;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;

/**
 * 初始 / 重置口令：调用者指定则校验格式，留空则服务端随机生成 ≥12 位强口令。
 *
 * <p>三个建人接口（03-02 §4.2 / §5.2 / §6.2 的 {@code initPassword}）与重置人员密码
 * （§3.6 的 {@code newPassword}）共用同一套规则，依据是 PRD F1-3 规则 3。
 *
 * <h2>为什么落在 {@code common/} 而不是 {@code org/member}</h2>
 * <p>它是<b>纯工具，没有任何业务判断</b>：不查库、不看租户、不认识节点。
 * 而消费方跨子域 —— {@code org/node} 的 §3.6 与 {@code org/member} 的三个建人接口。
 * 放 {@code org/member} 会让 {@code org/node} <b>反向依赖</b> {@code org/member}，
 * 而 {@code common} 谁都能依赖。与同包的 {@link PasswordHasher} / {@link SessionRevoker}
 * 是同一个位置选择，只是那两个还多一层 SPI（实现在 {@code auth}），本类不需要。
 *
 * <p><b>本类此前有两份实现</b>（{@code org/member/service/InitialPasswordFactory} 与
 * {@code org/node/service/NodePasswordResetService} 的两个私有方法），
 * 规则逐字相同。<b>已合并为本类，那两处均已删除</b> —— 于是「改一份忘了另一份」
 * 这个隐患不再存在，也不需要任何「两份必须同步」的警告。
 *
 * <h2>不设固定默认密码，也不用手机号后 6 位</h2>
 * <p>PRD F1-3 规则 3 逐字：「<b>严禁使用手机号后 6 位等可由账号推导的默认值</b>——
 * 用户名即手机号，同源意味着拿到名单即可登录任意账号」。固定常量同样不行：
 * 它会出现在文档与工单里，攻击者拿到用户名列表即可批量撞库命中所有「已建号未改密」的账号。
 *
 * <h2>明文只在本次响应返回一次</h2>
 * <p>四个接口的响应字段说明都写着「<b>仅本次返回一次</b>，不落库、不可再查」
 * （PRD §7.3：明文永不落库）。库里只有 BCrypt 密文，哈希一律走 {@link PasswordHasher} ——
 * 自己 {@code new BCryptPasswordEncoder} 会让 cost 分叉，而 BCrypt 把 cost 编码在密文里，
 * 两边都验得过，<b>不报错、不失败，只是安全强度悄悄回退</b>。
 */
@Component
public class InitialPasswordFactory {

    /** 留空时生成 <b>≥12 位</b>；取 16 位，与 03-01 §2.5 / 03-02 §3.6 同值。 */
    private static final int GENERATED_PASSWORD_LENGTH = 16;

    /** 去掉了易混字符（{@code I l 1 O 0}）：管理员要口头/短信转告本人。 */
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "!@#$%^&*-_=+";

    private final SecureRandom random = new SecureRandom();

    /**
     * 取本次要用的明文口令。
     *
     * @param specified 调用者指定的口令；{@code null} 或空白表示由服务端生成
     */
    public String resolve(String specified) {
        return specified == null || specified.isBlank()
                ? generate()
                : assertStrongEnough(specified);
    }

    /**
     * 「8~20 位且同时含字母与数字」——长度由 DTO 的 {@code @Size} 拦，
     * 这里判跨字符的那一半（正则表达可读性差）。不合规返回 <b>400</b>，不是业务码。
     *
     * <p><b>不含「不得与原密码相同」这一条</b>：那是 03-01 §1.6 <b>本人改密</b>的语义，
     * 而管理员建号 / 重置时不知道对方原密码，也不该知道。
     * 两处规则不同，共用一个方法迟早会把 §1.6 的规则漏进来 —— 与
     * {@link PasswordHasher} 刻意不暴露强度校验是同一条理由。
     */
    private static String assertStrongEnough(String raw) {
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "口令须同时包含字母与数字");
        }
        return raw;
    }

    /**
     * 四类字符各保底一个后打乱：只按字符池均匀抽样时，16 位里一个数字都没有的概率
     * 虽小却<b>不为零</b>，而那种口令过不了 03-01 §1.2 的登录侧校验 ——
     * 表现是「刚建的号用系统给的密码登不进去」。
     */
    private String generate() {
        String pool = UPPER + LOWER + DIGIT + SYMBOL;
        List<Character> chars = new ArrayList<>(GENERATED_PASSWORD_LENGTH);
        chars.add(UPPER.charAt(random.nextInt(UPPER.length())));
        chars.add(LOWER.charAt(random.nextInt(LOWER.length())));
        chars.add(DIGIT.charAt(random.nextInt(DIGIT.length())));
        chars.add(SYMBOL.charAt(random.nextInt(SYMBOL.length())));
        while (chars.size() < GENERATED_PASSWORD_LENGTH) {
            chars.add(pool.charAt(random.nextInt(pool.length())));
        }
        Collections.shuffle(chars, random);
        StringBuilder sb = new StringBuilder(chars.size());
        for (char c : chars) {
            sb.append(c);
        }
        return sb.toString();
    }
}
