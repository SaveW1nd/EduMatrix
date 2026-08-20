package com.edumatrix.common.grant;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.edumatrix.org.node.mapper.NodeGrantScopeMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D7：{@code valid_end} 上界的<b>唯一口径</b>是 {@code >= NOW()}，两条路径必须结论相同。
 *
 * <h2>它守的是一个当时就并存的分叉</h2>
 * <p>模块 06 交付的 {@code org/node/mapper/NodeGrantScopeMapper} 两条查询写的是
 * {@code valid_end > NOW()}，而全库唯一口径
 *（{@code ResourceGrantMapper.VALID_NOW}、02-数据库设计 §3.3.2 的鉴权 SQL、DDL 列注释）
 * 是 {@code >=}。两者<b>只在到期那一秒结论相反</b>：
 * <ul>
 *   <li>授权引擎说「这条还有效」（{@code >=}）；
 *   <li>节点移动说「这条已失效，不算跨管辖授权」（{@code >}）。
 * </ul>
 * <p>后果是<b>移动响应说没有跨管辖授权、实际有一条卡在边界上</b>，
 * 而两边都返回 200 —— 本项目 1 号失败模式。模块 11 在 C2 把它收敛掉，本类钉住结果。
 *
 * <h2>怎么让「到期那一秒」可复现</h2>
 * <p>用 <b>MySQL 自己的时钟</b>对齐到刚跨过整秒，再插入 {@code valid_end = NOW()}
 *（截断到该秒），此后约 950ms 内两条查询都落在同一秒里 —— 也就是
 * {@code valid_end == NOW()} 这个<b>边界本身</b>。用 JVM 时钟对齐是不行的：
 * 容器与宿主机的时钟不必相同，那会让这条用例变成偶发绿。
 */
class GrantValidityBoundaryIT extends CourseIntegrationTestBase {

    @Autowired
    private ResourceGrantReader grantReader;

    @Autowired
    private NodeGrantScopeMapper nodeGrantScopeMapper;

    private static final long GRANT_ID = 1968000000000009001L;

    @Test
    @DisplayName("valid_end 恰好等于当前秒：授权引擎与节点侧【结论相同】，且都判为有效")
    void bothPathsAgreeOnTheExpirySecond() throws Exception {
        alignToSecondBoundary();

        jdbcTemplate.update("INSERT INTO org_resource_grant (id, resource_type, resource_id, "
                        + "target_node_id, valid_start, valid_end, grant_source, grant_by, "
                        + "grant_time, tenant_id, create_time, update_time, deleted_at) "
                        + "VALUES (?, 1, ?, ?, NULL, NOW(), 1, ?, NOW(), ?, NOW(), NOW(), 0)",
                GRANT_ID, CourseFixtures.C_ROOT, CourseFixtures.TA,
                CourseFixtures.userIdOf(CourseFixtures.ROOT), CourseFixtures.TENANT_ID);

        try {
            runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.TA, () -> {
                boolean engineSaysActive =
                        grantReader.hasGrant(ResourceType.COURSE, CourseFixtures.C_ROOT,
                                CourseFixtures.TA);
                boolean nodeSideSaysActive = countedByNodeSide();

                assertThat(nodeSideSaysActive)
                        .as("两条路径在到期那一秒结论不同 —— 这正是 D7：授权引擎说有效、"
                                + "节点移动说无效，于是移动响应会说「没有跨管辖授权」而实际有一条，"
                                + "且两边都返回 200")
                        .isEqualTo(engineSaysActive);
                assertThat(engineSaysActive)
                        .as("唯一口径是 valid_end >= NOW()（02-数据库设计 §3.3.2 / DDL 列注释）："
                                + "到期当秒仍然有效。若这里为 false，说明【两条都】被改成了 >，"
                                + "上一条断言会因「一起错」而看不出来")
                        .isTrue();
            });
        } finally {
            jdbcTemplate.update("DELETE FROM org_resource_grant WHERE id = ?", GRANT_ID);
        }
    }

    /** 节点侧口径：§3.2 的 {@code grantedResourceStat} 里有没有算上这一行。 */
    private boolean countedByNodeSide() {
        List<NodeGrantScopeMapper.ResourceTypeCountRow> rows =
                nodeGrantScopeMapper.selectGrantedResourceStat(CourseFixtures.TA);
        return rows.stream()
                .filter(row -> row.getResourceType() != null && row.getResourceType() == 1)
                .anyMatch(row -> row.getCnt() != null && row.getCnt() > 0);
    }

    /**
     * 睡到<b>刚跨过整秒</b>，此后约 950ms 内的 {@code NOW()} 都落在同一秒。
     *
     * <p>用 {@code SELECT NOW(3)} 取<b>数据库</b>的时钟 —— 用 JVM 的会让这条用例
     * 在「容器与宿主机时钟有偏移」时变成偶发绿，而偶发绿比红更难查。
     */
    private void alignToSecondBoundary() throws InterruptedException {
        LocalDateTime dbNow = jdbcTemplate.queryForObject("SELECT NOW(3)", LocalDateTime.class);
        long msIntoSecond = dbNow == null ? 0L : dbNow.getNano() / 1_000_000L;
        Thread.sleep(1000L - msIntoSecond + 30L);
    }
}
