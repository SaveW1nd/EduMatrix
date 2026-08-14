package com.edumatrix.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.system.support.TenantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判据 2：<b>中断第 2 步后回滚，库中不留孤儿租户行</b>（03-01 §5.3：「步骤②③任一失败则
 * 整个事务回滚，不会留下『有租户无根节点』或『有根节点无管理员』的半成品」）。
 *
 * <h2>两条路径，验的不是同一件事</h2>
 * <table border="1">
 *   <caption>两条中断路径</caption>
 *   <tr><th>路径</th><th>失败点</th><th>要回滚的</th></tr>
 *   <tr><td>用户名冲突（{@code 10001}）</td><td>②a 插 {@code sys_user}</td>
 *       <td>步骤①的租户行</td></tr>
 *   <tr><td>父节点查不到（{@code 10101}）</td><td>②b 插 {@code org_node}</td>
 *       <td>步骤①的租户行 <b>+ ②a 的账号行</b></td></tr>
 * </table>
 * <p>第二条覆盖的是第一条到不了的地方：账号已经插进去了，此时失败必须把它一并抹掉，
 * 否则留下的正是 §5.3 说的"有根节点无管理员"的镜像——<b>有账号无节点</b>，
 * 而那个账号占着用户名，重试开通会撞 {@code 10001}，看起来像"名字被别人占了"。
 *
 * <h2>为什么第二条不用 {@code @MockBean}</h2>
 * <p>{@code AuthIntegrationTestBase} 的类注释立了一条硬规矩：{@code TenantHelper} 的
 * provider 是<b>静态字段</b>，而 {@code @MockBean} 会改变 Spring 测试上下文的缓存键、
 * 造出第二个上下文，把那个静态字段指向新上下文的 provider——先前上下文里的测试类
 * 再跑就会读到别人的 provider。<b>那条警告不绕。</b>
 * 改用数据手段（临时隐藏平台根哨兵行）让第②步的建节点这一小步真实失败，
 * {@code try/finally} 立刻还原。这样失败点落在真实代码路径上，比打桩更接近判据要验的东西。
 */
class TenantProvisionRollbackIT extends TenantIntegrationTestBase {

    @Test
    @DisplayName("判据 2①｜②a 插账号时用户名冲突 → 整体回滚，库中不留孤儿租户行")
    void usernameConflictRollsBackTheTenantRow() throws Exception {
        // 先占掉用户名
        assertThat(code(createTenant("占位机构", "taken"))).isEqualTo(200);

        JsonNode response = createTenant("回滚机构甲", "taken");

        assertThat(code(response)).isEqualTo(10001);
        // 步骤① 已经插过 sys_tenant，必须随事务回滚 —— 否则库里躺着一个
        // root_node_id 永远为 NULL 的孤儿租户行，而它还占着 uk_name
        assertThat(tenantFixtures.tenantRowCount(tenantName("回滚机构甲"))).isZero();
    }

    @Test
    @DisplayName("判据 2②｜②b 建节点失败 → 租户行与账号行【都】不留")
    void nodeCreationFailureRollsBackTenantAndAccount() throws Exception {
        JsonNode response;
        tenantFixtures.hidePlatformRootNode();
        try {
            response = createTenant("回滚机构乙", "rollback");
        } finally {
            // 还原必须在断言之前发生：断言失败会中断方法，而这一行不能被跳过
            tenantFixtures.restorePlatformRootNode();
        }

        // 父节点查不到 → PlatformNodeWriter#requireParent 抛 10101
        assertThat(code(response)).isEqualTo(10101);
        // 三张表一行都不留：①的租户行、②a 的账号行、②b 本身
        assertThat(tenantFixtures.tenantRowCount(tenantName("回滚机构乙"))).isZero();
        assertThat(tenantFixtures.userRowCount(adminUsername("rollback"))).isZero();

        // 回滚后重试必须能成功 —— 若上一次残留了账号，这里会撞 10001。
        // 这一条断言把"回滚干净"从"查不到行"升级成"业务上真的可重试"
        assertThat(code(createTenant("回滚机构乙", "rollback"))).isEqualTo(200);
    }

    @Test
    @DisplayName("判据 2｜回滚后平台根 child_count 不被多加（计数与节点同事务）")
    void rollbackDoesNotLeakChildCount() throws Exception {
        Integer before = tenantFixtures.platformRootChildCount();
        assertThat(code(createTenant("计数占位", "cnt"))).isEqualTo(200);

        JsonNode failed = createTenant("计数回滚", "cnt");

        assertThat(code(failed)).isEqualTo(10001);
        // 成功那次 +1，失败那次 0 —— child_count 是在同一事务里维护的，
        // 漏掉这条约束的表现是：反复失败的开通会把平台根的 child_count 越垫越高，
        // 而它是 10108「节点下存在子节点，不可删除」的判据
        assertThat(tenantFixtures.platformRootChildCount()).isEqualTo(before + 1);
    }
}
