package com.edumatrix.org.member.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;

/**
 * 初始密码：调用者指定则校验格式，留空则服务端随机生成 ≥12 位强口令
 * （PRD F1-3 规则 3、03-02 §4.2 / §5.2 / §6.2 的 {@code initPassword} 参数说明）。
 *
 * <h2>⚠ 全库还有一份同源实现：{@code org/node/service/NodePasswordResetService}</h2>
 * <p>模块 06 的 §3.6 重置人员密码里有两个私有方法（{@code generatePassword} /
 * {@code assertStrongEnough}），规则与本类<b>逐字相同</b>。本类<b>没有</b>把那两个方法抽走，
 * 原因是模块 06 正在整改、同期改同一个文件会在合并时把注释合掉一半 ——
 * 与 {@code PlatformNodeWriter} 退休被推迟是同一个理由。
 *
 * <p><b>模块 06 整改合入后，{@code NodePasswordResetService} 应改为委派本类，那两个私有方法删除。</b>
 * 在那之前<b>改任一份都要同时改另一份</b>：两份各自的测试都会继续通过，
 * 只是同一条口令规则在两条路径上一严一松，而<b>宽的那条不会报错</b>
 * （{@code NodeTypeRule} 的第二份实现是同一种处境，那里的注释写的也是这句）。
 *
 * <h2>不设固定默认密码，也不用手机号后 6 位</h2>
 * <p>PRD F1-3 规则 3 逐字：「<b>严禁使用手机号后 6 位等可由账号推导的默认值</b>——
 * 用户名即手机号，同源意味着拿到名单即可登录任意账号」。固定常量同样不行：
 * 它会出现在文档与工单里，攻击者拿到用户名列表即可批量撞库命中所有「已建号未改密」的账号。
 *
 * <h2>明文只在本次响应返回一次</h2>
 * <p>三个建人接口的响应字段说明都写着「<b>仅本次创建响应返回一次</b>，不落库、不可再查」
 * （PRD §7.3：明文永不落库）。库里只有 BCrypt 密文，哈希一律走
 * {@code common/account/PasswordHasher}（SPI，实现在 {@code auth}）——
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
     * 取本次建号要用的明文口令。
     *
     * @param specified 请求体里的 {@code initPassword}；{@code null} 或空白表示由服务端生成
     */
    public String resolve(String specified) {
        return specified == null || specified.isBlank()
                ? generate()
                : assertStrongEnough(specified);
    }

    /**
     * 「8~20 位且同时含字母与数字」——长度由 DTO 的 {@code @Size} 拦，
     * 这里判跨字符的那一半。不合规返回 <b>400</b>，不是业务码。
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
            throw new BizException(ErrorCode.BAD_REQUEST, "初始密码须同时包含字母与数字");
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
