package com.edumatrix.auth;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * refreshToken <b>一次性使用</b>的验收（00-通用约定 §2.2 规则 3）。
 *
 * <h2>它防的是一个安全属性，不是性能问题</h2>
 * <p>「一次性使用」的设计意图是：<b>令牌被盗时，攻击者与用户谁先用谁拿到新的，另一个立刻失效，
 * 泄露由此暴露</b>。若同一个 refreshToken 能换出两个新令牌，两边都能一直用下去，
 * <b>泄露永远不会被发现</b>。
 *
 * <p>此前的实现里「取值」（GET）与「删除」（DEL）分成两步，中间隔着 {@code selectById}
 * 与 {@code assertLoginable} 至少三次数据库往返 —— 并发的两个刷新会双双取到有效值。
 * 而这不是理论形态：<b>accessToken 过期的那一刻，前端若有两个业务请求在飞，两个都会触发刷新</b>；
 * 弱网超时重发同理。
 *
 * <h2>为什么这两条测试不会「偶尔绿」</h2>
 * <p>并发测试常见的写法是断言「必须观察到竞争」，那种断言在 CI 上会随机红。
 * 这里断言的是<b>「恰好一个成功」</b> —— 它在两种执行序下都成立：
 * 两个线程真的撞上时成立，完全错开时也成立（后者退化成第二条用例）。
 * 而它要防的那个 bug（非原子的取值+删除）<b>在两种执行序下都会让它失败</b>。
 * 稳定，且有效。
 */
class RefreshTokenAtomicityIT extends AuthIntegrationTestBase {

    @Test
    @DisplayName("并发｜同一个 refreshToken 两个线程同时刷新，恰好一个成功、一个 10006")
    void concurrentRefreshWithSameTokenYieldsExactlyOneSuccess() throws Exception {
        JsonNode login = client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        String refreshToken = login.path("data").path("refreshToken").asText();

        assertThat(refreshIndexSize()).as("登录后索引集合里只有这一个令牌").isEqualTo(1);

        // 两个线程在同一个栅栏上等，尽量让 GETDEL 真的撞上
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> {
                startLine.await(5, TimeUnit.SECONDS);
                return client.refresh(refreshToken).path("code").asInt();
            };
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            startLine.countDown();

            List<Integer> codes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(codes)
                    .as("一个旧令牌只能换出一个新令牌；两个都成功 = 「一次性使用」失效 = "
                            + "令牌泄露永远暴露不了")
                    .containsExactlyInAnyOrder(200, ErrorCode.REFRESH_TOKEN_INVALID.getCode());
        } finally {
            pool.shutdownNow();
        }

        assertThat(refreshIndexSize())
                .as("赢家：SREM 旧 hash + 加入新 hash；输家：在 openSession 之前就抛了，什么都没加。"
                        + "所以集合里恒为 1 个成员 —— 若是 2 个，说明签出了两个新令牌")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("顺序重放｜同一个 refreshToken 连用两次，第二次必须 10006")
    void replayingSameTokenFailsSecondTime() throws Exception {
        JsonNode login = client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        String refreshToken = login.path("data").path("refreshToken").asText();

        assertThat(client.refresh(refreshToken).path("code").asInt())
                .as("第一次：正常旋转")
                .isEqualTo(200);

        assertThat(client.refresh(refreshToken).path("code").asInt())
                .as("第二次：旧令牌在第一次的 GETDEL 里就没了（§2.2 规则 3 一次性使用，防重放）")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID.getCode());

        assertThat(refreshIndexSize())
                .as("索引集合里只剩旋转出来的那一个")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("消费失败的令牌不留痕：格式非法的串直接 10006，不去 Redis 转一圈")
    void malformedTokenIsRejectedWithoutTouchingRedis() throws Exception {
        client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        int before = refreshIndexSize();

        assertThat(client.refresh("../../frozen:1960000000000000001").path("code").asInt())
                .as("别让外部输入决定要去读/删哪个 key —— 与 CaptchaService 校验 captchaKey 同源")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID.getCode());

        assertThat(refreshIndexSize()).isEqualTo(before);
    }

    /** {@code auth:refresh:uid:{userId}} 当前的成员数。 */
    private int refreshIndexSize() {
        Set<String> members = redisTemplate.opsForSet()
                .members(RedisKeys.refreshTokenUserIndex(AuthFixtures.ADMIN_USER));
        return members == null ? 0 : members.size();
    }
}
