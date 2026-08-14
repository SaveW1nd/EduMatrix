package com.edumatrix.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.common.response.BizException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉死 {@link TokenService#consumeRefreshToken} 的<b>原子性实现方式</b>：
 * 必须走 {@code GETDEL}，不得退化为 {@code GET} + 后置 {@code DEL}。
 *
 * <h2>为什么并发测试不够，还要这一条</h2>
 * <p>{@code RefreshTokenAtomicityIT} 的并发用例<b>抓回归的能力是概率性的</b>：
 * 它要在两个线程真的撞进同一个窗口时才能发现问题，而真实非原子实现的窗口只有
 * <b>几毫秒</b>（{@code selectById} + {@code assertLoginable} 三次数据库往返）。
 * 同栅栏释放大概率能撞上，但不必然。而顺序重放那条用例在非原子实现下<b>照样绿</b>
 * —— 它验的是语义（用过就不能再用），不是竞争。
 *
 * <p>所以：并发用例只能说「<b>竞争发生时能抓住</b>」，
 * 加上本条才能说「<b>改回非原子必然被抓住</b>」。本条<b>不依赖任何时序</b>，
 * 它直接断言打给 Redis 的是哪个命令。
 *
 * <h2>为什么两条断言都要有</h2>
 * <ul>
 *   <li>{@code getAndDelete} 被调用一次 —— 保证原子命令确实发出去了；
 *   <li>{@code get} <b>一次都没有</b> —— 只断言前者不够。非原子实现是
 *       「{@code get} 之后再 {@code delete}」，此时 {@code getAndDelete} 根本不出现；
 *       但某些改法下两者可能<b>同时存在</b>（比如先 {@code get} 判断再 {@code getAndDelete}），
 *       那依然是把窗口打开了。禁掉 {@code get} 才能把这条路也堵上。
 * </ul>
 *
 * <h2>为什么是纯单元测试</h2>
 * <p>不起 Spring 上下文、不碰 Redis：{@code TokenService} 只从构造器要一个
 * {@link StringRedisTemplate}，直接 {@code new} 出来即可。
 * 走 {@code @SpyBean} 的话会改变测试上下文的缓存键、<b>创建第二个 Spring 上下文</b> ——
 * 而 {@code TenantHelper} 的 provider 是静态字段，第二个上下文会把它指向自己的实例，
 * 打断先前上下文里的用例（详见 {@code AuthIntegrationTestBase} 类注释）。
 */
class TokenServiceAtomicityTest {

    /** 合法形状的令牌：43 位 URL-safe Base64 字符（{@code TokenService.TOKEN_PATTERN}）。 */
    private static final String VALID_TOKEN = "A".repeat(43);

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TokenService tokenService;

    @BeforeEach
    @SuppressWarnings("unchecked") // mock 泛型接口拿不到类型实参，这是 Mockito 的常规写法
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        tokenService = new TokenService(redisTemplate);
    }

    @Test
    @DisplayName("consumeRefreshToken 必须原子消费：走 GETDEL，不得退化为 GET + DEL")
    void mustConsumeAtomicallyWithGetDel() {
        when(valueOps.getAndDelete(anyString())).thenReturn("1960000000000000111:1960000000000000001");

        TokenService.RefreshTokenRecord record = tokenService.consumeRefreshToken(VALID_TOKEN);

        assertThat(record.userId()).isEqualTo(1960000000000000111L);
        assertThat(record.tenantId()).isEqualTo(1960000000000000001L);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(1)).getAndDelete(key.capture());
        assertThat(key.getValue())
                .as("消费的是 refreshToken 的 key，且存的是哈希不是原文")
                .startsWith(RedisKeys.REFRESH_TOKEN_PREFIX)
                .doesNotContain(VALID_TOKEN);

        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("取不到值即 10006：GETDEL 返回 null 时不得回退到 GET 再看一眼")
    void missingTokenIsRejectedWithoutFallbackGet() {
        when(valueOps.getAndDelete(anyString())).thenReturn(null);

        assertThatThrownBy(() -> tokenService.consumeRefreshToken(VALID_TOKEN))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));

        verify(valueOps, times(1)).getAndDelete(anyString());
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("形状不合法的串直接拒，不打给 Redis —— 别让外部输入决定要读/删哪个 key")
    void malformedTokenNeverReachesRedis() {
        assertThatThrownBy(() -> tokenService.consumeRefreshToken("../../frozen:1"))
                .isInstanceOf(BizException.class);

        verify(valueOps, never()).getAndDelete(anyString());
        verify(valueOps, never()).get(anyString());
    }
}
