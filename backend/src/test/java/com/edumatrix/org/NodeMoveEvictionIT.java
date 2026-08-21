package com.edumatrix.org;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.org.support.OrgFixtures;
import com.edumatrix.org.support.OrgIntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约 §2.4：<b>「学员被移走后，原上级立即失去对其全部数据的访问权」</b>。
 *
 * <p>这句承诺的<b>唯一落地机制</b>是「事务提交后递归删除被移动子树的 {@code node:anc:*} 键」
 * ——鉴权与数据权限选路都从 {@code NodeAncestorCache} 取祖先链，
 * 不删就等于原上级在缓存 TTL（30 分钟）内继续持有旧的可见范围。
 *
 * <h2>断言的是<b>业务行为</b>，不是 Redis 键</h2>
 * <p>只断言「键没了」不够：键没了但业务仍放行（比如某条路径压根不查缓存、或查了不用），
 * 承诺照样是空的。所以这里验的是<b>原导师再去读那个学员的详情，必须 404</b>。
 * 键的存在只用来<b>证明缓存确实是热的</b> —— 否则「移动后 404」可能只是因为
 * 缓存本来就没被填过，测试会在一个不存在的机制上通过。
 */
class NodeMoveEvictionIT extends OrgIntegrationTestBase {

    @Test
    @DisplayName("学员被移走后，原导师立刻查不到他（先证明缓存是热的，再断言业务行为变了）")
    void formerParentLosesAccessImmediately() throws Exception {
        String teacherToken = loginAs(OrgFixtures.T3);
        String adminToken = loginAs(OrgFixtures.ROOT);

        // ① 移动之前：原导师 T3 读得到名下学员 S8 的详情
        assertThat(code(client.getWithToken("/api/v1/org/nodes/" + OrgFixtures.S8, teacherToken)))
                .isEqualTo(200);

        // ② 这一步把 node:anc:{S8} 填热了 —— 没有它，第 ④ 步的 404 证明不了任何事
        assertThat(redisTemplate.hasKey(RedisKeys.nodeAncestors(OrgFixtures.S8)))
                .as("子树判定应已把 S8 的祖先链写进缓存")
                .isTrue();

        // ③ 管理员把 S8 从 T3 转给 TX
        assertThat(code(move(adminToken, OrgFixtures.S8, OrgFixtures.TX))).isEqualTo(200);

        // ④ 【业务行为变了】原导师立刻失去访问权——不是等 30 分钟 TTL 过期
        assertThat(code(client.getWithToken("/api/v1/org/nodes/" + OrgFixtures.S8, teacherToken)))
                .as("原上级必须立即失去对被移走学员的访问权（契约 §2.4）")
                .isEqualTo(404);

        // ⑤ 新导师读得到（说明 404 是「不在你的子树里」，不是「这个人没了」）
        String newTeacherToken = loginAs(OrgFixtures.TX);
        assertThat(code(client.getWithToken("/api/v1/org/nodes/" + OrgFixtures.S8, newTeacherToken)))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("整棵子树的祖先链缓存都要清，不只是被移动节点自己那一个键")
    void wholeSubtreeIsEvicted() throws Exception {
        String adminToken = loginAs(OrgFixtures.ROOT);

        // 先把 P 子树里几个节点的祖先链都填热
        for (long nodeId : new long[]{OrgFixtures.T1, OrgFixtures.S1, OrgFixtures.S2, OrgFixtures.S3}) {
            assertThat(code(client.getWithToken("/api/v1/org/nodes/" + nodeId, adminToken)))
                    .isEqualTo(200);
        }
        assertThat(redisTemplate.hasKey(RedisKeys.nodeAncestors(OrgFixtures.S1))).isTrue();

        assertThat(code(move(adminToken, OrgFixtures.T1, OrgFixtures.A2))).isEqualTo(200);

        // 被移动节点自身与它的后代，键都必须没了 ——
        // 只清自己那一个的话，S1 的祖先链会在 TTL 内一直是旧的那条
        for (long nodeId : new long[]{OrgFixtures.T1, OrgFixtures.S1, OrgFixtures.S2, OrgFixtures.S3}) {
            assertThat(redisTemplate.hasKey(RedisKeys.nodeAncestors(nodeId)))
                    .as("节点 " + nodeId + " 的祖先链缓存应已被递归清除")
                    .isFalse();
        }
    }
}
