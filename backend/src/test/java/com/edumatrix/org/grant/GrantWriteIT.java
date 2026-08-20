package com.edumatrix.org.grant;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块 11 · C4：接口 38 授权资源给节点（03-02 §9.2）。
 *
 * <p>七条校验逐条对着 §9.2 的表，<b>四个错误码互不代偿</b>：
 * {@code 10301} 答「这个资源我能不能授」、{@code 10302} 答「这个目标我能不能授给」、
 * {@code 10303} 答「这一对已经授过了」、{@code 10308} 答「这个类型不该授给这种节点」。
 */
class GrantWriteIT extends GrantIntegrationTestBase {

    private static final String GRANTS = "/api/v1/org/grants";

    /** 分批插入用例的课程号段 —— 仍在 1971 前缀内，与夹具的固定课程不重叠。 */
    private static final long BULK_COURSE_BASE = 1971000000000005001L;

    // =====================================================================
    // 正常路径
    // =====================================================================

    @Test
    @DisplayName("接口 38：N 个资源 × M 个节点，写入 N×M 行")
    void cartesianProductIsWritten() throws Exception {
        JsonNode resp = postWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d","%d"],"targetNodeIds":["%d","%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.C3,
                GrantFixtures.A1, GrantFixtures.A2)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("grantedCount").asInt()).isEqualTo(4);
        assertThat(data(resp).path("resourceCount").asInt()).isEqualTo(2);
        assertThat(data(resp).path("targetNodeCount").asInt()).isEqualTo(2);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1)).isEqualTo(2);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C3)).isEqualTo(2);
    }

    @Test
    @DisplayName("接口 38：逐级下发链能原样跑通（PRD FR-2 的 N0→N1→N2→N3）")
    void fourLevelChain() throws Exception {
        grantOk(GrantFixtures.ROOT, 1, GrantFixtures.C1, GrantFixtures.A1);
        grantOk(GrantFixtures.A1, 1, GrantFixtures.C1, GrantFixtures.T1);
        grantOk(GrantFixtures.T1, 1, GrantFixtures.C1, GrantFixtures.S[0]);

        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1))
                .as("每一跳都是一次独立写入，四级链共 3 行")
                .isEqualTo(3);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.S[1]))
                .as("没被授到的学员一行都没有 —— 不向下继承（契约 §2.5 规则 3）")
                .isZero();
    }

    // =====================================================================
    // 10301：资源拥有性
    // =====================================================================

    @Test
    @DisplayName("⚠ 10301：注解放行 ≠ 有权授权 —— 下级管理员确实持有 org:grant:grant")
    void permissionPassesButResourceDenied() throws Exception {
        String token = loginAs(GrantFixtures.A1);

        JsonNode me = getWithToken("/api/v1/auth/me", token);
        List<String> perms = new ArrayList<>();
        data(me).path("perms").forEach(node -> perms.add(node.asText()));
        assertThat(perms)
                .as("功能权限这一维【确实通过了】—— 本用例要证的正是「通过 ≠ 有权授权」")
                .contains("org:grant:grant");

        JsonNode resp = postWithToken(GRANTS, token, body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C3, GrantFixtures.T1)));

        assertThat(code(resp))
                .as("C3 是 ROOT 拥有但从未授予 A1 的。三个权限维度互相独立，"
                        + "只有数据范围随树收缩（04 §B 模块 11 规则 18）")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND_OR_NO_GRANT_RIGHT.getCode());
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C3)).isZero();
    }

    @Test
    @DisplayName("⚠ 10301：「资源不存在」与「你无权」的响应【逐字节相同】（防探测）")
    void notFoundAndNoRightAreIndistinguishable() throws Exception {
        String token = loginAs(GrantFixtures.A1);

        // (a) 根本不存在的资源 ID
        JsonNode missing = postWithToken(GRANTS, token, body("""
                {"resourceType":1,"resourceIds":["1971000000000009999"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.T1)));
        // (b) 存在、但上级从未授予我的资源
        JsonNode noRight = postWithToken(GRANTS, token, body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C3, GrantFixtures.T1)));

        assertThat(missing.toString())
                .as("两种情况的响应必须【逐字节相同】：只要 code/msg/data 有任何一处能区分，"
                        + "攻击者拿一批 ID 挨个试就能把别人的资源清单枚举出来（PRD FR-1 规则 2、"
                        + "与 F-42 同一条推理）。分别断言「都是 10301」是【测不出】这件事的 —— "
                        + "msg 里多一句「资源不存在」照样两条都是 10301")
                .isEqualTo(noRight.toString());
        assertThat(code(missing))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND_OR_NO_GRANT_RIGHT.getCode());
    }

    @Test
    @DisplayName("10301：跨管辖资源授不出去 —— 与接口 37 的清单同一个判定")
    void crossScopeCannotBeRegranted() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        moveT1UnderA2();

        JsonNode resp = postWithToken(GRANTS, loginAs(GrantFixtures.T1), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.S[0])));
        assertThat(code(resp))
                .as("契约 §2.5 规则 9：调岗后仍可【使用】，但丧失【再下发】能力。"
                        + "接口 37 已把它从清单里滤掉，这里必须拒 —— 两处同一个判定")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND_OR_NO_GRANT_RIGHT.getCode());
    }

    // =====================================================================
    // 10302 / 10101 / 10109：目标节点
    // =====================================================================

    @Test
    @DisplayName("10302：平级不可互授（PRD FR-2 规则 4）")
    void siblingGrantIsRejected() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);

        JsonNode resp = postWithToken(GRANTS, loginAs(GrantFixtures.A1), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A2)));
        assertThat(code(resp)).isEqualTo(ErrorCode.GRANT_TARGET_OUT_OF_SUBTREE.getCode());
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.A2)).isZero();
    }

    @Test
    @DisplayName("10302：向上授权同样被拒（教师授给自己的上级）")
    void upwardGrantIsRejected() throws Exception {
        // 【两条都要造】只授 C1→T1 而不授 C1→A1，造出来的是一条【悬挂授权】：
        // T1 的链在 A1 那一层断了，canRegrant 判假，于是先撞 10301 而根本走不到 10302。
        // 要测「目标越界」就得先让资源那一维【合法】—— §9.2 的校验表里 10301 排在 10302 前面
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);

        assertThat(code(postWithToken(GRANTS, loginAs(GrantFixtures.T1), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)))))
                .isEqualTo(ErrorCode.GRANT_TARGET_OUT_OF_SUBTREE.getCode());
    }

    @Test
    @DisplayName("10101：目标节点不存在；任一越界整批不落行")
    void missingTargetRejectsWholeBatch() throws Exception {
        JsonNode resp = postWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d","1971000000000009998"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)));

        assertThat(code(resp)).isEqualTo(ErrorCode.NODE_NOT_FOUND.getCode());
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1))
                .as("§9.2 校验表开头逐字：任一失败即整体回滚，【不做部分成功】。"
                        + "合法的那个目标也不能落行")
                .isZero();
    }

    @Test
    @DisplayName("10109：目标节点已停用")
    void disabledTargetIsRejected() throws Exception {
        jdbcTemplate.update("UPDATE org_node SET status = 1 WHERE id = ?", GrantFixtures.A1);

        assertThat(code(postWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)))))
                .isEqualTo(ErrorCode.NODE_DISABLED.getCode());
    }

    // =====================================================================
    // 10308：题目 / 视频不得授给学生节点
    // =====================================================================

    @Test
    @DisplayName("10308：题目与视频都不得授给学生节点；课程可以")
    void questionAndVideoCannotGoToStudents() throws Exception {
        String token = loginAs(GrantFixtures.ROOT);

        assertThat(code(postWithToken(GRANTS, token, body("""
                {"resourceType":2,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.Q1, GrantFixtures.S[0])))))
                .as("契约 §2.5 规则 11：学生侧没有题目的直接使用入口，"
                        + "这类行永远不会被任何鉴权路径读到，只会污染悬挂巡检")
                .isEqualTo(ErrorCode.RESOURCE_TYPE_NOT_GRANTABLE_TO_STUDENT.getCode());

        assertThat(code(postWithToken(GRANTS, token, body("""
                {"resourceType":3,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.V1, GrantFixtures.S[0])))))
                .isEqualTo(ErrorCode.RESOURCE_TYPE_NOT_GRANTABLE_TO_STUDENT.getCode());

        assertThat(code(postWithToken(GRANTS, token, body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.S[0])))))
                .as("课程【可以】授给学生 —— 只有授到学生节点，学生端才看得见（PRD FR-1 规则 6）")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("10308：题目授给教师节点是允许的（备课 / 组卷）")
    void questionToTeacherIsFine() throws Exception {
        assertThat(code(postWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":2,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.Q1, GrantFixtures.T1)))))
                .isEqualTo(200);
    }

    // =====================================================================
    // 10303：重复授权
    // =====================================================================

    @Test
    @DisplayName("10303：默认整批回滚；ignoreDuplicate=true 时跳过并列出明细")
    void duplicateHandling() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        String token = loginAs(GrantFixtures.ROOT);

        JsonNode strict = postWithToken(GRANTS, token, body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d","%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.A2)));
        assertThat(code(strict)).isEqualTo(ErrorCode.GRANT_ALREADY_EXISTS.getCode());
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1, GrantFixtures.A2))
                .as("默认 ignoreDuplicate=false 命中即整批回滚，防止误覆盖已有有效期")
                .isZero();

        JsonNode lenient = postWithToken(GRANTS, token, body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d","%d"],
                 "ignoreDuplicate":true}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.A2)));
        assertThat(code(lenient)).isEqualTo(200);
        assertThat(data(lenient).path("grantedCount").asInt()).isEqualTo(1);
        assertThat(data(lenient).path("duplicatedCount").asInt()).isEqualTo(1);
        JsonNode dup = data(lenient).path("duplicated").get(0);
        assertThat(dup.path("resourceName").asText()).isEqualTo("高三数学·函数与导数");
        assertThat(dup.path("targetNodeName").asText()).isEqualTo("华东大区");
    }

    // =====================================================================
    // 5000 上限
    // =====================================================================

    @Test
    @DisplayName("⚠ 5000 上限是【资源数 × 目标节点数】，不是任一侧的条数")
    void rowLimitIsTheProductNotEitherSide() throws Exception {
        // 500 个资源 × 20 个节点 = 10000 行：两侧都 ≤ 500，【乘积超限】。
        // 只判任一侧的写法会照样放行，后果是一个同步事务里一万行写入
        String resourceIds = idArray(4000000000000000001L, 500);
        String targetNodeIds = idArray(4000000000000001001L, 20);

        JsonNode resp = postWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":%s,"targetNodeIds":%s}
                """.formatted(resourceIds, targetNodeIds)));

        assertThat(code(resp)).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(resp.path("msg").asText())
                .as("提示里要写清是乘积超限并建议分批，否则调用方会以为是某一侧超了")
                .contains("10000").contains("分批");
    }

    @Test
    @DisplayName("5000 上限：超限时【DB 一行都不碰】（在任何查库之前拒）")
    void rowLimitRejectsBeforeTouchingDb() throws Exception {
        long before = countAllGrants();
        postWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":%s,"targetNodeIds":%s}
                """.formatted(idArray(4000000000000000001L, 500),
                idArray(4000000000000001001L, 20))));
        assertThat(countAllGrants())
                .as("那 500 个资源 ID 全是不存在的 —— 若先去查了一圈再拒，"
                        + "本断言仍然过，但 10301 会【先于】400 抛出。用响应码那条一起看")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("分批插入：1000 行跨两个 chunk，一行不能少（切片写错不会报错，只会少写）")
    void multiChunkInsertWritesEveryRow() throws Exception {
        // INSERT_CHUNK = 500，1000 行必然跨两批。切片下标写错（比如漏掉最后一段）
        // 的表现是【grantedCount 报 1000、库里只有 500】—— 接口 200、字段齐全、结果错。
        // 上面那些用例最多写 4 行，一条都覆盖不到这条路径。
        // 【行数靠乘积凑，不靠单侧】：两个数组各自的上限是 500（§9.2），
        // 所以 500 个资源 × 2 个节点 = 1000 行，两侧都合法而行数跨批
        int courses = 500;
        int total = courses * 2;
        List<Object[]> args = new ArrayList<>(courses);
        for (int i = 0; i < courses; i++) {
            args.add(new Object[]{BULK_COURSE_BASE + i, "批量课程" + i, GrantFixtures.ROOT,
                    GrantFixtures.TENANT_ID, GrantFixtures.userIdOf(GrantFixtures.ROOT)});
        }
        jdbcTemplate.batchUpdate("INSERT INTO crs_course (id, course_name, owner_node_id, "
                + "cover_file_id, subject, description, status, lesson_count, total_duration, "
                + "tenant_id, create_by, create_time, update_time, deleted_at) "
                + "VALUES (?, ?, ?, NULL, '数学', '简介', 1, 0, 0, ?, ?, NOW(), NOW(), 0)", args);

        JsonNode resp = postWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":%s,"targetNodeIds":["%d","%d"]}
                """.formatted(idArray(BULK_COURSE_BASE, courses),
                GrantFixtures.A1, GrantFixtures.A2)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("grantedCount").asInt()).isEqualTo(total);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_resource_grant WHERE target_node_id IN (?, ?) "
                        + "AND deleted_at = 0",
                Integer.class, GrantFixtures.A1, GrantFixtures.A2))
                .as("库里的行数必须与 grantedCount 一致 —— 报了 1000 实际 500 是不报错的")
                .isEqualTo(total);
    }

    // ================================================================ 辅助

    private void grantOk(long asNode, int resourceType, long resourceId, long targetNodeId)
            throws Exception {
        JsonNode resp = postWithToken(GRANTS, loginAs(asNode), body("""
                {"resourceType":%d,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(resourceType, resourceId, targetNodeId)));
        assertThat(code(resp)).as("授权应成功，实际响应：%s", resp).isEqualTo(200);
    }

    private long countAllGrants() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_resource_grant WHERE tenant_id = ?",
                Long.class, GrantFixtures.TENANT_ID);
        return n == null ? 0L : n;
    }

    private static String idArray(long base, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(base + i).append('"');
        }
        return sb.append(']').toString();
    }

    private static String body(String json) {
        return json.replace("\n", " ");
    }

    /** 把 T1 挪到 A2 名下（不经接口，本类不测移动，只要那个树形）。 */
    private void moveT1UnderA2() {
        String underA2 = "0," + GrantFixtures.ROOT + "," + GrantFixtures.A2;
        jdbcTemplate.update("UPDATE org_node SET parent_id = ?, ancestors = ? WHERE id = ?",
                GrantFixtures.A2, underA2, GrantFixtures.T1);
        jdbcTemplate.update("UPDATE org_node SET ancestors = ? WHERE parent_id = ?",
                underA2 + "," + GrantFixtures.T1, GrantFixtures.T1);
        cleanGrantRedisKeys();
    }
}
