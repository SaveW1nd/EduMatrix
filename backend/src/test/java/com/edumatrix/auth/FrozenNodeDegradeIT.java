package com.edumatrix.auth;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.edumatrix.common.frozen.FrozenNodeCache;
import com.edumatrix.common.frozen.mapper.FrozenNodeMapper;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.mapper.OrgNodeSubtreeMapper;
import com.edumatrix.common.tenant.TenantHelper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 判据 4 的后半：<b>断开 Redis 后冻结校验仍然生效</b>（走查库降级）。
 *
 * <h2>怎么模拟「Redis 不可用」</h2>
 * <p>用一个连<b>死端口</b>的 {@link StringRedisTemplate} 现场构造一个
 * {@link FrozenNodeCache}，其余两个依赖（{@link FrozenNodeMapper} /
 * {@link OrgNodeSubtreeMapper}）注入的是真 Bean、连的是真库。
 * <b>不用 mock</b>：mock 只能证明「我以为它会抛异常时会走那个分支」，
 * 而这里要证明的是真的连不上时会怎样。
 *
 * <p><b>为什么不通过 HTTP 验</b>：把拦截器用的那个 {@code FrozenNodeCache} 换掉需要
 * {@code @TestConfiguration} 或 {@code @MockBean}，两者都会改变 Spring 测试上下文的缓存键、
 * 从而<b>创建第二个上下文</b> —— 而 {@code TenantHelper} 的 provider 是静态字段，
 * 第二个上下文会把它指向自己的 provider，先前上下文里的模块 01 用例再跑就会读到别人的会话
 * （详见 {@code AuthIntegrationTestBase} 类注释）。这里调用的
 * {@link FrozenNodeCache#isFrozen} 与拦截器调的是<b>同一个方法</b>，覆盖的是同一段判定。
 *
 * <h2>断言为什么足够强</h2>
 * <p>全程<b>没有往冻结集里写过任何成员</b>。如果实现是「缓存挂了就放行」，
 * 或者干脆是「Redis 里没有就放行」，这里必然返回 false。返回 true 只可能来自查库那一支。
 */
class FrozenNodeDegradeIT extends AuthIntegrationTestBase {

    @Autowired
    private FrozenNodeMapper frozenNodeMapper;

    @Autowired
    private OrgNodeSubtreeMapper orgNodeSubtreeMapper;

    private LettuceConnectionFactory deadFactory;

    @AfterEach
    void closeDeadRedis() {
        if (deadFactory != null) {
            deadFactory.destroy();
        }
    }

    @Test
    @DisplayName("判据 4b｜Redis 不可用时，冻结校验降级查库仍然生效（不是跳过）")
    void degradesToDatabaseWhenRedisIsDown() {
        FrozenNodeCache degraded = new FrozenNodeCache(
                deadRedisTemplate(), frozenNodeMapper, orgNodeSubtreeMapper);

        // 停用管理员分支：只写库，冻结集一个成员都不写
        fixtures.setNodeStatus(AuthFixtures.ADMIN_NODE, 1);

        TenantHelper.runWithTenant(AuthFixtures.TENANT_ID, () -> {
            List<Long> studentAncestors = List.of(AuthFixtures.ROOT_NODE, AuthFixtures.ADMIN_NODE);

            assertThat(degraded.isFrozen(
                    AuthFixtures.TENANT_ID, AuthFixtures.STUDENT1_NODE, studentAncestors))
                    .as("契约 §2.3：这是安全校验，不是缓存加速；"
                            + "『缓存挂了就放行』等于停用功能整体失效")
                    .isTrue();

            assertThat(degraded.isFrozen(
                    AuthFixtures.TENANT_ID, AuthFixtures.ADMIN_NODE, List.of(AuthFixtures.ROOT_NODE)))
                    .as("①段：本人节点被停用")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("判据 4b 的反面｜降级查库同样保留 node_type=1 —— 教师停用不级联")
    void degradedPathKeepsNodeTypeCondition() {
        FrozenNodeCache degraded = new FrozenNodeCache(
                deadRedisTemplate(), frozenNodeMapper, orgNodeSubtreeMapper);

        fixtures.setNodeStatus(AuthFixtures.TEACHER_NODE, 1);

        TenantHelper.runWithTenant(AuthFixtures.TENANT_ID, () -> {
            assertThat(degraded.isFrozen(AuthFixtures.TENANT_ID, AuthFixtures.STUDENT2_NODE,
                    List.of(AuthFixtures.ROOT_NODE, AuthFixtures.TEACHER_NODE)))
                    .as("降级路径与登录侧共用同一条 SQL，②段的 node_type=1 不会因为降级而丢")
                    .isFalse();

            assertThat(degraded.isFrozen(AuthFixtures.TENANT_ID, AuthFixtures.TEACHER_NODE,
                    List.of(AuthFixtures.ROOT_NODE)))
                    .isTrue();
        });
    }

    @Test
    @DisplayName("写侧不吞异常：Redis 不可用时 SADD 必须抛出，好让停用事务回滚")
    void writeSideFailsLoudly() {
        FrozenNodeCache degraded = new FrozenNodeCache(
                deadRedisTemplate(), frozenNodeMapper, orgNodeSubtreeMapper);

        assertThatThrownBy(() -> degraded.add(AuthFixtures.TENANT_ID, AuthFixtures.ADMIN_NODE))
                .as("吞掉就成了「库停了、冻结集没记」—— 正是「先 SADD 再提交」要避免的放行窗口")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("祖先链的输入形状：parseAncestorIds 已跳过首位哨兵 0，降级查询不受影响")
    void ancestorIdsSkipRootSentinel() {
        List<Long> ids = NodePath.parseAncestorIds("0," + AuthFixtures.ROOT_NODE + ","
                + AuthFixtures.ADMIN_NODE);
        assertThat(ids)
                .as("哨兵是 node_type=0，永远不命中②段；跳过它也避免了按 IN 查名称时的空行困惑")
                .containsExactly(AuthFixtures.ROOT_NODE, AuthFixtures.ADMIN_NODE);
    }

    /** 连一个确定关闭的端口 —— 这就是「Redis 不可用」。 */
    private StringRedisTemplate deadRedisTemplate() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", 1);
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(300))
                .build();
        deadFactory = new LettuceConnectionFactory(config, clientConfig);
        deadFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(deadFactory);
        template.afterPropertiesSet();
        return template;
    }
}
