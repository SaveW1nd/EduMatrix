package com.edumatrix.org.grant;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块 11 · C6：接口 40 修改授权有效期（03-02 §9.4）。
 *
 * <p>头号用例是 <b>PRD FR-1 验收标准原文</b>：
 * 「教师 T 对课程 K 的授权有效期至 12-31、其名下 30 名学员各自持有该课程授权，
 * 把 T 的有效期改为 06-30，Then T 与 30 名学员的授权行<b>全部原地更新、一条不删</b>，
 * 晚于 06-30 的子树行截断为 06-30；若改为延长至次年 12-31，则学员行不变。」
 * （本夹具是 8 名学员，形状相同。）
 */
class GrantValidityIT extends GrantIntegrationTestBase {

    private static final String VALIDITY = "/api/v1/org/grants/validity";
    private static final String GRANTS = "/api/v1/org/grants";

    // =====================================================================
    // FR-1 规则 4
    // =====================================================================

    @Test
    @DisplayName("⚠ FR-1：缩短 → 9 行全部【原地更新】、id 不变、一条不删，子树一并截断")
    void shortenTruncatesSubtreeAndDeletesNothing() throws Exception {
        seedTeacherAndStudents("2027-12-31 23:59:59");
        List<Long> idsBefore = grantRowIds();
        assertThat(idsBefore).hasSize(9);

        JsonNode resp = putWithToken(VALIDITY, loginAs(GrantFixtures.A1), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validEnd":"2026-06-30 23:59:59"}
                """.formatted(GrantFixtures.C1, GrantFixtures.T1)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("cascadeTruncatedCount").asInt())
                .as("8 名学员的行被连带截断")
                .isEqualTo(8);

        assertThat(grantRowIds())
                .as("【原地更新】：主键集合逐个不变。若实现成「撤销 + 重授」，"
                        + "这里会是一组全新的 id，而且只剩 1 行")
                .containsExactlyInAnyOrderElementsOf(idsBefore);
        assertThat(grantFixtures.activeGrantCount(1, GrantFixtures.C1))
                .as("一条都不能删。10 = A1 那一行 + T1 + 8 名学员 —— "
                        + "若实现成「撤销 + 重授」，撤销会级联清空 T1 与 8 名学员，这里会变成 2")
                .isEqualTo(10);
        assertThat(validEndOf(GrantFixtures.T1)).isEqualTo("2026-06-30 23:59:59");
        for (long student : GrantFixtures.S) {
            assertThat(validEndOf(student))
                    .as("子树内晚于新值的行一并截断（防时间维度悬挂）")
                    .isEqualTo("2026-06-30 23:59:59");
        }
    }

    @Test
    @DisplayName("⚠ FR-1：延长 → 子树【一行不动】（收紧自动传导，放松需要显式操作）")
    void extendDoesNotCascade() throws Exception {
        seedTeacherAndStudents("2026-06-30 23:59:59");

        JsonNode resp = putWithToken(VALIDITY, loginAs(GrantFixtures.A1), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validEnd":"2027-12-31 23:59:59"}
                """.formatted(GrantFixtures.C1, GrantFixtures.T1)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("cascadeTruncatedCount").asInt())
                .as("§9.4 响应字段说明：延长时恒为 0")
                .isZero();
        assertThat(validEndOf(GrantFixtures.T1)).isEqualTo("2027-12-31 23:59:59");
        for (long student : GrantFixtures.S) {
            assertThat(validEndOf(student))
                    .as("下级的有效期是上级当初主动收窄的结果，延长上级不应擅自替下级放宽")
                    .isEqualTo("2026-06-30 23:59:59");
        }
    }

    @Test
    @DisplayName("⚠ 原值永久 → 改为具体日期【算缩短】，子树一并截断（null 是最晚不是最早）")
    void permanentToDatedIsAShortening() throws Exception {
        seedTeacherAndStudents(null);

        JsonNode resp = putWithToken(VALIDITY, loginAs(GrantFixtures.A1), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validEnd":"2026-06-30 23:59:59"}
                """.formatted(GrantFixtures.C1, GrantFixtures.T1)));

        assertThat(data(resp).path("cascadeTruncatedCount").asInt())
                .as("把 null 当成「最早」会把这次判成延长而不级联 —— 结果是上级从永久收到 06-30，"
                        + "而子树整片仍然永久有效，一次「收紧」什么都没收紧，且不报错")
                .isEqualTo(8);
        assertThat(validEndOf(GrantFixtures.S[0])).isEqualTo("2026-06-30 23:59:59");
    }

    @Test
    @DisplayName("改为永久（传 null）算延长 —— 不级联")
    void datedToPermanentIsAnExtension() throws Exception {
        seedTeacherAndStudents("2026-06-30 23:59:59");

        JsonNode resp = putWithToken(VALIDITY, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d","validEnd":null}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("cascadeTruncatedCount").asInt()).isZero();
        assertThat(validEndOf(GrantFixtures.A1)).isNull();
    }

    @Test
    @DisplayName("⚠ 「不传 validEnd」与「传 null」是两个意思：只改 validStart 不该抹掉到期日")
    void absentFieldKeepsOriginalValue() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT,
                null, "2026-06-30 23:59:59");

        JsonNode resp = putWithToken(VALIDITY, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validStart":"2026-01-01 00:00:00"}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(validEndOf(GrantFixtures.A1))
                .as("不区分「不传」与「传 null」的实现会把到期日抹成永久 —— "
                        + "一次续期把到期日删掉了，而接口返回 200")
                .isEqualTo("2026-06-30 23:59:59");
    }

    // =====================================================================
    // 截断与校验顺序
    // =====================================================================

    @Test
    @DisplayName("超出授权人自身有效期 → 自动截断，不报错（契约 §2.5 规则 7）")
    void validEndIsCappedByMyOwnHolding() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT,
                null, "2026-12-31 23:59:59");
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1,
                null, "2026-06-30 23:59:59");

        JsonNode resp = putWithToken(VALIDITY, loginAs(GrantFixtures.A1), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validEnd":"2027-06-30 23:59:59"}
                """.formatted(GrantFixtures.C1, GrantFixtures.T1)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("validEndTruncated").asBoolean()).isTrue();
        assertThat(data(resp).path("effectiveValidEnd").asText()).isEqualTo("2026-12-31 23:59:59");
        assertThat(validEndOf(GrantFixtures.T1)).isEqualTo("2026-12-31 23:59:59");
    }

    @Test
    @DisplayName("⚠ F-86：越界节点一律 10302，【不泄露那条授权在不在】")
    void outOfSubtreeAlwaysReturns10302() throws Exception {
        // T2 在 A2 分支下，对 T1 而言越界。造两种情形：有授权行 / 没有授权行
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T2, GrantFixtures.ROOT);

        JsonNode withRow = putWithToken(VALIDITY, loginAs(GrantFixtures.T1), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validEnd":"2027-06-30 23:59:59"}
                """.formatted(GrantFixtures.C1, GrantFixtures.T2)));
        JsonNode withoutRow = putWithToken(VALIDITY, loginAs(GrantFixtures.T1), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validEnd":"2027-06-30 23:59:59"}
                """.formatted(GrantFixtures.C3, GrantFixtures.T2)));

        assertThat(withRow.toString())
                .as("照 §9.4 的表先判「授权行存在」，两种情形会分别返回 10302 与 10307 —— "
                        + "两码可区分就能凭错误码枚举别人节点持有哪些资源，"
                        + "与 10301 那条防探测（契约 §2.5 规则 1、F-42）是同一件事")
                .isEqualTo(withoutRow.toString());
        assertThat(code(withRow)).isEqualTo(ErrorCode.GRANT_TARGET_OUT_OF_SUBTREE.getCode());
    }

    @Test
    @DisplayName("10307：子树内但授权行不存在")
    void missingRowIs10307() throws Exception {
        assertThat(code(putWithToken(VALIDITY, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validEnd":"2027-06-30 23:59:59"}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)))))
                .isEqualTo(ErrorCode.GRANT_RECORD_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("10301：我自己已不再拥有该资源时不能改它的有效期")
    void noLongerOwnedIs10301() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        // 撤掉 A1 自己那一行（连带 T1），再单独把 T1 的行补回来 —— 造出「A1 无权、T1 有行」
        deleteWithToken(GRANTS, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceIds":["%d"],"targetNodeIds":["%d"]}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)));
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);

        assertThat(code(putWithToken(VALIDITY, loginAs(GrantFixtures.A1), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validEnd":"2027-06-30 23:59:59"}
                """.formatted(GrantFixtures.C1, GrantFixtures.T1)))))
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND_OR_NO_GRANT_RIGHT.getCode());
    }

    @Test
    @DisplayName("400：validStart >= validEnd")
    void invalidPeriodIs400() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);

        assertThat(code(putWithToken(VALIDITY, loginAs(GrantFixtures.ROOT), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d",
                 "validStart":"2027-01-01 00:00:00","validEnd":"2026-01-01 00:00:00"}
                """.formatted(GrantFixtures.C1, GrantFixtures.A1)))))
                .isEqualTo(ErrorCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("⚠ 自查补的：被授权者把下级改为「永久」时，仍被截断到我自己的到期日")
    void granteeCannotGrantPermanentBeyondOwnCap() throws Exception {
        // 这条覆盖的是 earlier(requested=null, cap!=null) 这一支 —— 上面 10 条用例
        // 一次都没执行到它：datedToPermanentIsAnExtension 跑的是 ROOT（owner，cap 为 null）。
        // 漏掉它的后果：下级被授到「永久」，而授权人自己 12-31 就到期 ——
        // 正是契约 §2.5 规则 7 要防的时间维度悬挂，且接口返回 200
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT,
                null, "2026-12-31 23:59:59");
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1,
                null, "2026-06-30 23:59:59");

        JsonNode resp = putWithToken(VALIDITY, loginAs(GrantFixtures.A1), body("""
                {"resourceType":1,"resourceId":"%d","targetNodeId":"%d","validEnd":null}
                """.formatted(GrantFixtures.C1, GrantFixtures.T1)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("validEndTruncated").asBoolean())
                .as("请求的是「永久」，而我自己 2026-12-31 到期 —— 必须截断")
                .isTrue();
        assertThat(validEndOf(GrantFixtures.T1))
                .as("落库必须是我的上界，不能是 NULL")
                .isEqualTo("2026-12-31 23:59:59");
    }

    // ================================================================ 辅助

    /** A1 → T1 → 8 名学员，T1 与学员的 valid_end 都是 {@code end}（{@code null} = 永久）。 */
    private void seedTeacherAndStudents(String end) {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT, null, null);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1, null, end);
        for (long student : GrantFixtures.S) {
            grantFixtures.grant(1, GrantFixtures.C1, student, GrantFixtures.T1, null, end);
        }
    }

    /** T1 与 8 名学员那 9 行的主键（不含 A1 那一行）。 */
    private List<Long> grantRowIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM org_resource_grant WHERE resource_id = ? AND deleted_at = 0 "
                        + "AND target_node_id <> ? ORDER BY id",
                Long.class, GrantFixtures.C1, GrantFixtures.A1);
    }

    private String validEndOf(long targetNodeId) {
        return jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(valid_end, '%Y-%m-%d %H:%i:%s') FROM org_resource_grant "
                        + "WHERE resource_id = ? AND target_node_id = ? AND deleted_at = 0",
                String.class, GrantFixtures.C1, targetNodeId);
    }

    private static String body(String json) {
        return json.replace("\n", " ");
    }
}
