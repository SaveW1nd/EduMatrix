package com.edumatrix.org.grant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块 11 · C10：接口 52 归属变更影响面预检（03-02 §6.12、F-21 定案、F-80）。
 *
 * <p><b>它此前是 161 个接口里唯一无人认领的那个</b>：{@code 00-通用约定.md}:465
 * 写着「签名在模块 07 敲定，<b>实现落在模块 11</b>」，而 04 §A 的核对行把它算进了
 * 模块 07 的 21 个 —— §B 模块 07 的表只有 20 行。那一行自称「验证没有接口无人认领」，
 * 却只校验求和、不校验每个模块的数与 §B 表行数是否一致。
 */
class TransferPrecheckIT extends GrantIntegrationTestBase {

    private static final String PRECHECK = "/api/v1/org/students/transfer-precheck";

    @Test
    @DisplayName("⚠ 分配导师：把学员转给不持有该课的教师 → 影响面被摊开")
    void assignTeacherRevealsTheImpact() throws Exception {
        // ROOT → A1 → T1 → 学员；T2 在 A2 分支下，A2 与 T2 都不持有 C1
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.S[0], GrantFixtures.T1);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.S[1], GrantFixtures.T1);

        JsonNode resp = postWithToken(PRECHECK, loginAs(GrantFixtures.ROOT), body("""
                {"studentIds":["%d","%d"],"toNodeId":"%d"}
                """.formatted(studentIdOf(GrantFixtures.S[0]), studentIdOf(GrantFixtures.S[1]),
                GrantFixtures.T2)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("action").asInt())
                .as("动作类型由 toNodeId 的 node_type 推断，与接口 4 的 changeType 推断同源")
                .isEqualTo(2);
        assertThat(data(resp).path("actionName").asText()).isEqualTo("分配导师");

        JsonNode row = data(resp).path("outOfScopeGrants").get(0);
        assertThat(row.path("resourceName").asText()).isEqualTo("高三数学·函数与导数");
        assertThat(row.path("affectedStudentCount").asInt())
                .as("按【资源】归并，不按人 —— 500 人逐人弹窗不可用")
                .isEqualTo(2);
        assertThat(row.path("grantableByMe").asBoolean())
                .as("ROOT 是 C1 的 owner，授得了 —— 前端可引导他去接口 38 补授给 T2")
                .isTrue();
        assertThat(data(resp).path("summary").path("affectedStudentCount").asInt()).isEqualTo(2);
        assertThat(data(resp).path("learningRecordsRetained").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("⚠ 链在移动后仍然完整 → 影响面为空（不把「管辖没变」的塞进待办）")
    void intactChainAfterMoveReportsNothing() throws Exception {
        // 目标教师 T1 与其上级 A1 都持有 C1：学员转到 T1 名下后链仍然通
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.S9, GrantFixtures.T2);

        JsonNode resp = postWithToken(PRECHECK, loginAs(GrantFixtures.ROOT), body("""
                {"studentIds":["%d"],"toNodeId":"%d"}
                """.formatted(studentIdOf(GrantFixtures.S9), GrantFixtures.T1)));

        assertThat(code(resp)).isEqualTo(200);
        assertThat(data(resp).path("outOfScopeGrants").size()).isZero();
        assertThat(data(resp).path("summary").path("resourceCount").asInt()).isZero();
    }

    @Test
    @DisplayName("⚠ 无权授予的资源【不返回 10301】，以 grantableByMe=false 标记")
    void notGrantableIsFlaggedNotRejected() throws Exception {
        // C2 是 A1 【自建】的：ROOT 是 A1 的上级，但【归属不向上流动】——
        // ROOT 既不是 owner、也没有被授予 C2，所以他授不了它。
        // 演员必须是 ROOT 而不是 A2：A2 看不到 A1 子树里的学员，会先撞 10107 而走不到这里
        grantFixtures.grant(1, GrantFixtures.C2, GrantFixtures.T1, GrantFixtures.A1);
        grantFixtures.grant(1, GrantFixtures.C2, GrantFixtures.S[0], GrantFixtures.T1);

        JsonNode resp = postWithToken(PRECHECK, loginAs(GrantFixtures.ROOT), body("""
                {"studentIds":["%d"],"toNodeId":"%d"}
                """.formatted(studentIdOf(GrantFixtures.S[0]), GrantFixtures.T2)));

        assertThat(code(resp))
                .as("10301 是【执行接口 38 时】的拒绝码；在只读预检里抛它会让整个预检失败，"
                        + "而操作者恰恰需要看到「这门课我授不了，得找共同上级」")
                .isEqualTo(200);
        JsonNode row = data(resp).path("outOfScopeGrants").get(0);
        assertThat(row.path("grantableByMe").asBoolean()).isFalse();
        assertThat(data(resp).path("summary").path("notGrantableCount").asInt()).isEqualTo(1);
        assertThat(data(resp).path("summary").path("grantableByMeCount").asInt()).isZero();
    }

    @Test
    @DisplayName("转交管理员：action = 3")
    void transferAdminInfersActionThree() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.S[0], GrantFixtures.T1);

        JsonNode resp = postWithToken(PRECHECK, loginAs(GrantFixtures.ROOT), body("""
                {"studentIds":["%d"],"toNodeId":"%d"}
                """.formatted(studentIdOf(GrantFixtures.S[0]), GrantFixtures.A2)));

        assertThat(data(resp).path("action").asInt()).isEqualTo(3);
        assertThat(data(resp).path("actionName").asText()).isEqualTo("转交管理员");
    }

    @Test
    @DisplayName("options：keep 恒排第一且 isDefault=true（F-21 定案第 1 条）")
    void keepIsAlwaysTheDefault() throws Exception {
        JsonNode options = data(postWithToken(PRECHECK, loginAs(GrantFixtures.ROOT), body("""
                {"studentIds":["%d"],"toNodeId":"%d"}
                """.formatted(studentIdOf(GrantFixtures.S[0]), GrantFixtures.T2))))
                .path("options");

        assertThat(options.get(0).path("value").asText())
                .as("强制二选一等于逼操作者在「给导师一堆用不上的授权」和「把学员的课停掉」"
                        + "之间挑一个 —— 默认必须是第三个选项：什么都不做")
                .isEqualTo("keep");
        assertThat(options.get(0).path("isDefault").asBoolean()).isTrue();
        assertThat(options.get(1).path("description").asText())
                .as("F-21 定案第 2 条：撤销选项的文案必须写明学习记录一律保留")
                .contains("保留不删");
    }

    @Test
    @DisplayName("10104：目标既非管理员也非教师；10107：目标越界（与接口 20/21/22 同一判定）")
    void badTargetAndOutOfScope() throws Exception {
        assertThat(code(postWithToken(PRECHECK, loginAs(GrantFixtures.ROOT), body("""
                {"studentIds":["%d"],"toNodeId":"%d"}
                """.formatted(studentIdOf(GrantFixtures.S[0]), GrantFixtures.S[1])))))
                .isEqualTo(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID.getCode());

        assertThat(code(postWithToken(PRECHECK, loginAs(GrantFixtures.A1), body("""
                {"studentIds":["%d"],"toNodeId":"%d"}
                """.formatted(studentIdOf(GrantFixtures.S[0]), GrantFixtures.T2)))))
                .as("§6.12 数据权限栏：与接口 20/21/22【同一判定】，预检与执行不得有两套口径")
                .isEqualTo(ErrorCode.TARGET_NODE_OUT_OF_SCOPE.getCode());
    }

    @Test
    @DisplayName("教师没有 org:student:transfer → 403（§6.12：仅 org_admin）")
    void teacherIsForbidden() throws Exception {
        assertThat(code(postWithToken(PRECHECK, loginAs(GrantFixtures.T1), body("""
                {"studentIds":["%d"],"toNodeId":"%d"}
                """.formatted(studentIdOf(GrantFixtures.S[0]), GrantFixtures.T2)))))
                .isEqualTo(403);
    }

    /** 学生档案 ID = 节点 ID + 500（见 {@code GrantFixtures#student}）。 */
    private static long studentIdOf(long nodeId) {
        return nodeId + 500;
    }

    private static String body(String json) {
        return json.replace("\n", " ");
    }
}
