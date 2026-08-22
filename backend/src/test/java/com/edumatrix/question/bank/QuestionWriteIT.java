package com.edumatrix.question.bank;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.question.support.QuestionFixtures;
import com.edumatrix.question.support.QuestionIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题目写侧四接口（03-04 §2.2 / §2.3 / §2.7 / §2.8，目录 6 / 7 / 11 / 12）。
 */
class QuestionWriteIT extends QuestionIntegrationTestBase {

    private static final String QUESTIONS = "/api/v1/question/questions";

    private String single(String answerJson) {
        return """
                {"categoryId":"%d","questionType":1,"difficulty":3,
                 "content":{"stem":"方程的根是（　）","options":[
                   {"key":"A","text":"1"},{"key":"B","text":"2"},
                   {"key":"C","text":"3"},{"key":"D","text":"4"}]},
                 "correctAnswer":%s,"scoreDefault":5.0,"status":1}
                """.formatted(QuestionFixtures.CAT_ALGEBRA, answerJson);
    }

    // ================================================================ 接口 6 创建

    @Test
    @DisplayName("接口 6 创建单选题：owner_node_id 由服务端写入创建者所在节点，请求体不接受")
    void createSingleChoiceWritesOwnerFromSession() throws Exception {
        // 演员从教师王（TA）换成下级管理员 A1：V202608210200 之后教师没有
        // question:question:add。本条验的是「owner 由服务端按会话写入、请求体说了不算」，
        // 换成一个【不是 ROOT】的下级管理员，这个判据一字不变。
        // 【F-114】题目写操作收窄到机构根：创建者从 A1 换成 ROOT。
        // 本条要证的是「owner 取自会话节点而不是请求体」，与谁能写无关，换演员不影响它。
        JsonNode created = postWithToken(QUESTIONS, loginAs(QuestionFixtures.ROOT),
                single("{\"answer\":\"A\"}"));
        assertEquals(200, code(created));
        long id = data(created).path("id").asLong();
        assertEquals(1, data(created).path("currentVersion").asInt());
        assertTrue(data(created).path("childIds").isArray());
        assertEquals(0, data(created).path("childIds").size(), "普通题的 childIds 是空数组，不是 null");

        Long owner = jdbcTemplate.queryForObject(
                "SELECT owner_node_id FROM qb_question WHERE id = ?", Long.class, id);
        assertEquals(QuestionFixtures.ROOT, owner, "owner 必须是创建者所在节点，不是请求体给的");
    }

    /**
     * <b>需方 2026-08-21 定案（排期 A）的判据之一 —— 题目侧代表端点。</b>
     *
     * <p>收窄靠迁移 {@code V202608210200} 撤销 {@code teacher → question:question:add}
     * 的绑定，<b>不靠代码里的角色门</b>。两侧都断言的理由与
     * {@code CourseCrudIT#createCourseIsOrgAdminOnly} 逐字相同：只断教师那一侧的话，
     * 把端点整个删掉也全绿。
     */
    @Test
    @DisplayName("⚠ 接口 6 新建题目【仅 org_admin】：教师 403、管理员 200（需方定案，排期 A）")
    void createQuestionIsOrgAdminOnly() throws Exception {
        String body = single("{\"answer\":\"A\"}");

        assertEquals(403, code(postWithToken(QUESTIONS, loginAs(QuestionFixtures.TA), body)),
                "教师拿不到建题端点（【结果】断言；成因见 teacherHasNoQuestionWritePerms）");

        assertEquals(200, code(postWithToken(QUESTIONS, loginAs(QuestionFixtures.ROOT), body)),
                "这一侧不写，等于把建题整个关掉也全绿");
    }

    @Test
    @DisplayName("⚠ F-72 的成因判据：教师的 perms 里【没有】question:question:add")
    void teacherHasNoQuestionWritePerms() throws Exception {
        // 与 CourseCrudIT#teacherHasNoCourseWritePerms 同一个来历：F-114 收窄之后
        // 「教师 403」两道闸都成立，403 说明不了成因。实测 M62 见那条的注释。
        assertFalse(permsOf(QuestionFixtures.TA).contains("question:question:add"),
                "F-72 撤的是 sys_role_menu 里那行绑定，不是代码里的角色门");
    }

    /** <b>三处静默故障之一</b>：判断题必须是 JSON 布尔字面量。 */
    @Test
    @DisplayName("接口 6 判断题答案是字符串 \"true\" → 400；布尔 true → 200（04 §B 自检 1、2）")
    void trueFalseAnswerMustBeBooleanLiteral() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        String body = """
                {"categoryId":"%d","questionType":3,"difficulty":2,
                 "content":{"stem":"同位角相等。"},
                 "correctAnswer":{"answer":%s},"scoreDefault":2.0,"status":1}
                """;
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), code(postWithToken(QUESTIONS, token,
                body.formatted(QuestionFixtures.CAT_MATH, "\"true\""))));
        assertEquals(200, code(postWithToken(QUESTIONS, token,
                body.formatted(QuestionFixtures.CAT_MATH, "true"))));
    }

    @Test
    @DisplayName("接口 6 多选落库是【规范形】：提交 [\"C\",\"A\"] 存进去是 [\"A\",\"C\"]")
    void multiChoiceIsStoredCanonical() throws Exception {
        JsonNode created = postWithToken(QUESTIONS, loginAs(QuestionFixtures.ROOT), """
                {"categoryId":"%d","questionType":2,"difficulty":4,
                 "content":{"stem":"正确的有（　）","options":[
                   {"key":"A","text":"a"},{"key":"B","text":"b"},
                   {"key":"C","text":"c"},{"key":"D","text":"d"}]},
                 "correctAnswer":{"answer":["C","A"]},"scoreDefault":6.0,"status":1}
                """.formatted(QuestionFixtures.CAT_ALGEBRA));
        assertEquals(200, code(created));
        String stored = jdbcTemplate.queryForObject(
                "SELECT correct_answer FROM qb_question_version WHERE question_id = ?",
                String.class, data(created).path("id").asLong());
        assertTrue(stored.replace(" ", "").contains("[\"A\",\"C\"]"),
                "库里出现了非规范形 —— 规范化必须在解析出口做完，实际：" + stored);
    }

    @Test
    @DisplayName("接口 6 结构与题型不匹配 → 30006（blankCount 对不上 / 选项号不存在 / 选项数越界）")
    void structuralMismatchIs30006() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        assertEquals(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getCode(),
                code(postWithToken(QUESTIONS, token, """
                        {"categoryId":"%d","questionType":4,"difficulty":2,
                         "content":{"stem":"中国的首都是____。","blankCount":2},
                         "correctAnswer":{"blanks":[{"index":1,"accepts":["北京"]}]},
                         "scoreDefault":4.0,"status":1}
                        """.formatted(QuestionFixtures.CAT_MATH))));
        assertEquals(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getCode(),
                code(postWithToken(QUESTIONS, token, single("{\"answer\":\"E\"}"))));
    }

    @Test
    @DisplayName("接口 6 材料题：父子一个事务，父题 scoreDefault 必须等于子题之和")
    void createMaterialQuestion() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        String body = """
                {"categoryId":"%d","questionType":6,"difficulty":4,
                 "content":{"stem":"阅读材料，回答问题"},
                 "scoreDefault":%s,"status":1,
                 "childQuestions":[
                   {"questionType":1,"sort":1,
                    "content":{"stem":"子题一（　）","options":[
                      {"key":"A","text":"a"},{"key":"B","text":"b"}]},
                    "correctAnswer":{"answer":"B"},"scoreDefault":4.0},
                   {"questionType":5,"sort":2,
                    "content":{"stem":"子题二"},
                    "correctAnswer":{"text":"参考答案"},"scoreDefault":6.0}]}
                """;
        assertEquals(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getCode(),
                code(postWithToken(QUESTIONS, token,
                        body.formatted(QuestionFixtures.CAT_MATH, "99.0"))),
                "父题分数 ≠ 子题之和 → 30006（契约 §5「父题分数 = 子题之和」）");

        JsonNode created = postWithToken(QUESTIONS, token,
                body.formatted(QuestionFixtures.CAT_MATH, "10.0"));
        assertEquals(200, code(created));
        long parentId = data(created).path("id").asLong();
        assertEquals(2, data(created).path("childIds").size());

        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM qb_question_version WHERE question_id = ?", String.class, parentId);
        assertTrue(content.contains("childOrder"), "父题版本必须记 childOrder —— 子题顺序唯一真相源");
        String parentAnswer = jdbcTemplate.queryForObject(
                "SELECT correct_answer FROM qb_question_version WHERE question_id = ?",
                String.class, parentId);
        assertEquals(null, parentAnswer, "材料题父题不存答案（契约 §5）");

        Integer children = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qb_question WHERE parent_id = ?", Integer.class, parentId);
        assertEquals(2, children);
    }

    @Test
    @DisplayName("接口 6 子题题型不能是 6（材料题不可嵌套）")
    void childCannotBeMaterial() throws Exception {
        assertEquals(ErrorCode.BAD_REQUEST.getCode(),
                code(postWithToken(QUESTIONS, loginAs(QuestionFixtures.ROOT), """
                        {"categoryId":"%d","questionType":6,"difficulty":4,
                         "content":{"stem":"材料"},"scoreDefault":4.0,"status":1,
                         "childQuestions":[{"questionType":6,"content":{"stem":"套娃"},
                                            "scoreDefault":4.0}]}
                        """.formatted(QuestionFixtures.CAT_MATH))));
    }

    // ================================================================ 接口 7 修改

    /** <b>三处静默故障之一</b>：历史版本不可修改、不可删除。 */
    @Test
    @DisplayName("接口 7 编辑写新版本，旧版本行【逐字段不变】（04 §B 自检第 4 条）")
    void editCreatesNewVersionAndLeavesHistoryByteIdentical() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        Map<String, Object> before = versionRow(QuestionFixtures.Q_SINGLE, 1);

        JsonNode updated = putWithToken(QUESTIONS + "/" + QuestionFixtures.Q_SINGLE, token, """
                {"content":{"stem":"方程的两个【实数】根是（　）","options":[
                   {"key":"A","text":"1 和 2"},{"key":"B","text":"b"},
                   {"key":"C","text":"c"},{"key":"D","text":"d"}]},
                 "correctAnswer":{"answer":"A"},"analysis":"改过的解析","scoreDefault":5.0}
                """);
        assertEquals(200, code(updated));
        assertEquals(2, data(updated).path("currentVersion").asInt());
        assertTrue(data(updated).path("versionCreated").asBoolean());

        assertEquals(before, versionRow(QuestionFixtures.Q_SINGLE, 1),
                "历史版本行被动过了 —— 契约 §4 与 PRD F3-2 规则 3 要求它不可修改");

        Integer current = jdbcTemplate.queryForObject(
                "SELECT current_version FROM qb_question WHERE id = ?", Integer.class,
                QuestionFixtures.Q_SINGLE);
        assertEquals(2, current);
        assertNotEquals(before.get("content"), versionRow(QuestionFixtures.Q_SINGLE, 2).get("content"),
                "新版本必须真的记了新内容，否则这条用例什么都没证明");
    }

    @Test
    @DisplayName("接口 7 只改分类/难度/备注 → 不产生新版本（versionCreated=false）")
    void metadataOnlyEditDoesNotBumpVersion() throws Exception {
        JsonNode updated = putWithToken(QUESTIONS + "/" + QuestionFixtures.Q_SINGLE,
                loginAs(QuestionFixtures.ROOT),
                "{\"categoryId\":\"" + QuestionFixtures.CAT_MATH + "\",\"difficulty\":5}");
        assertEquals(200, code(updated));
        assertFalse(data(updated).path("versionCreated").asBoolean());
        assertEquals(1, data(updated).path("currentVersion").asInt());
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qb_question_version WHERE question_id = ?", Integer.class,
                QuestionFixtures.Q_SINGLE));
    }

    @Test
    @DisplayName("接口 7 题型不可改：请求体压根没有 questionType 这个字段（PRD F3-2 规则 2）")
    void questionTypeIsNotUpdatable() throws Exception {
        // 传了也被忽略：题型仍是 1，且不因此产生新版本
        assertEquals(200, code(putWithToken(QUESTIONS + "/" + QuestionFixtures.Q_SINGLE,
                loginAs(QuestionFixtures.ROOT), "{\"questionType\":2,\"difficulty\":4}")));
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT question_type FROM qb_question WHERE id = ?", Integer.class,
                QuestionFixtures.Q_SINGLE));
    }

    @Test
    @DisplayName("接口 7 被授权者【只读可用】：可见但改 → 403（不是 404，也不是 200）")
    void grantedUserCannotEdit() throws Exception {
        // 【被授权者换成管理员 A1，不是「教师建不了所以换」】
        // 教师现在没有 question:question:edit，这条断言会【绿着退化】：
        // 403 从「可见但非 owner」变成「压根没这个权限」，而它要证的正是前者。
        // A1 是管理员、有 edit 权限，403 只可能来自归属判定。
        // ⚠【F-114 换演员】收窄后 A1 会在【机构根闸】处 403，本条会绿着退化成
        //   「A1 碰不到题目写端点」。Q_TA 的 owner 是教师 TA，演员换成机构根 ROOT：
        //   ROOT 过得了机构根闸、也有 question:question:edit，403 才真的来自归属判定。
        questionFixtures.grantQuestion(QuestionFixtures.Q_TA, QuestionFixtures.ROOT,
                QuestionFixtures.TENANT_ID);
        assertEquals(ErrorCode.FORBIDDEN.getCode(), code(putWithToken(
                QUESTIONS + "/" + QuestionFixtures.Q_TA, loginAs(QuestionFixtures.ROOT),
                "{\"difficulty\":5}")),
                "演员是【机构根】ROOT，过得了 F-114 的机构根闸、也有写权限位，"
                        + "403 只可能来自归属判定 —— Q_TA 的 owner 是 TA");
    }

    @Test
    @DisplayName("接口 7 不可见的题 → 404，与「不存在」同一个响应")
    void invisibleQuestionIsIndistinguishableFromMissing() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        HttpOutcome invisible = outcome("PUT", QUESTIONS + "/" + QuestionFixtures.Q_TA, token,
                "{\"difficulty\":5}");
        HttpOutcome absent = outcome("PUT", QUESTIONS + "/9999999999999999", token,
                "{\"difficulty\":5}");
        assertEquals(absent, invisible, "两者可区分即可被拿来探测存在性（F-42 口径）");
    }

    // ================================================================ 接口 11 启用停用

    @Test
    @DisplayName("接口 11 停用被作业引用的题 → 30001，且回显 referencedHomeworks")
    void disableReferencedQuestionIs30001() throws Exception {
        questionFixtures.homeworkReferencing(1969000000000009001L, QuestionFixtures.Q_SINGLE,
                QuestionFixtures.ROOT, QuestionFixtures.TENANT_ID, 0L, 0L);
        JsonNode failed = putWithToken(QUESTIONS + "/" + QuestionFixtures.Q_SINGLE + "/status",
                loginAs(QuestionFixtures.ROOT), "{\"status\":2}");
        assertEquals(ErrorCode.QUESTION_IN_USE_CANNOT_DISABLE.getCode(), code(failed));
        assertEquals(1, data(failed).path("referencedHomeworks").size());
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT status FROM qb_question WHERE id = ?", Integer.class,
                QuestionFixtures.Q_SINGLE), "被拒绝的停用不该留下副作用");
    }

    /** <b>F-76 定案的执行侧。</b> */
    @Test
    @DisplayName("F-76：作业本身已逻辑删除时【不算】引用 —— 否则题库会累积一批删不掉也停不了的题")
    void deletedHomeworkDoesNotCount() throws Exception {
        questionFixtures.homeworkReferencing(1969000000000009002L, QuestionFixtures.Q_SINGLE,
                QuestionFixtures.ROOT, QuestionFixtures.TENANT_ID, 1755000000000L, 0L);
        assertEquals(200, code(putWithToken(QUESTIONS + "/" + QuestionFixtures.Q_SINGLE + "/status",
                loginAs(QuestionFixtures.ROOT), "{\"status\":2}")));
    }

    @Test
    @DisplayName("F-76：选题行自身已逻辑删除时【不算】引用")
    void deletedHomeworkQuestionRowDoesNotCount() throws Exception {
        questionFixtures.homeworkReferencing(1969000000000009003L, QuestionFixtures.Q_SINGLE,
                QuestionFixtures.ROOT, QuestionFixtures.TENANT_ID, 0L, 1755000000000L);
        assertEquals(200, code(putWithToken(QUESTIONS + "/" + QuestionFixtures.Q_SINGLE + "/status",
                loginAs(QuestionFixtures.ROOT), "{\"status\":2}")));
    }

    @Test
    @DisplayName("接口 11 材料题父题停用【联动全部子题】；传子题 ID → 400（F-78）")
    void materialStatusCascadesAndChildIdRejected() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        assertEquals(200, code(putWithToken(
                QUESTIONS + "/" + QuestionFixtures.Q_MATERIAL + "/status", token, "{\"status\":2}")));
        assertEquals(2, (int) jdbcTemplate.queryForObject(
                "SELECT status FROM qb_question WHERE id = ?", Integer.class,
                QuestionFixtures.Q_CHILD_1), "子题状态没跟着父题走");

        assertEquals(ErrorCode.BAD_REQUEST.getCode(), code(putWithToken(
                QUESTIONS + "/" + QuestionFixtures.Q_CHILD_1 + "/status", token, "{\"status\":1}")),
                "以子题 ID 调本接口是说不通的请求 —— 静默按父题处理更糟");
    }

    @Test
    @DisplayName("接口 11 不允许改回 0 草稿 → 400")
    void cannotRevertToDraft() throws Exception {
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), code(putWithToken(
                QUESTIONS + "/" + QuestionFixtures.Q_SINGLE + "/status",
                loginAs(QuestionFixtures.ROOT), "{\"status\":0}")));
    }

    // ================================================================ 接口 12 删除

    @Test
    @DisplayName("接口 12 被引用 → 30005；未被引用 → 逻辑删除，且【版本表永不删除】")
    void deleteKeepsAllVersionRows() throws Exception {
        String token = loginAs(QuestionFixtures.ROOT);
        questionFixtures.homeworkReferencing(1969000000000009004L, QuestionFixtures.Q_SINGLE,
                QuestionFixtures.ROOT, QuestionFixtures.TENANT_ID, 0L, 0L);
        assertEquals(ErrorCode.QUESTION_IN_USE_CANNOT_REMOVE.getCode(),
                code(deleteWithToken(QUESTIONS + "/" + QuestionFixtures.Q_SINGLE, token)));

        assertEquals(200, code(deleteWithToken(QUESTIONS + "/" + QuestionFixtures.Q_MATERIAL, token)));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qb_question WHERE id = ? AND deleted_at = 0",
                Integer.class, QuestionFixtures.Q_MATERIAL));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qb_question WHERE parent_id = ? AND deleted_at = 0",
                Integer.class, QuestionFixtures.Q_MATERIAL), "删父题必须级联逻辑删子题");
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qb_question_version WHERE question_id = ? AND deleted_at = 0",
                Integer.class, QuestionFixtures.Q_MATERIAL),
                "版本表永不删除（03-04 §2.8）—— 已发布作业还要按固化版本渲染");
    }

    @Test
    @DisplayName("接口 12 删除题目【不】级联撤销 org_resource_grant 的授权行（契约 §2.5 规则 12）")
    void deleteKeepsGrantRows() throws Exception {
        questionFixtures.grantQuestion(QuestionFixtures.Q_MATERIAL, QuestionFixtures.TB,
                QuestionFixtures.TENANT_ID);
        assertEquals(200, code(deleteWithToken(QUESTIONS + "/" + QuestionFixtures.Q_MATERIAL,
                loginAs(QuestionFixtures.ROOT))));
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_resource_grant WHERE resource_type = 2 "
                        + "AND resource_id = ? AND deleted_at = 0",
                Integer.class, QuestionFixtures.Q_MATERIAL),
                "题目若恢复则原授权自动重新生效 —— 可用性由题目状态在使用侧承担");
    }

    // ================================================================ 工具

    /** 版本行的全部业务列 + 审计列，用于「逐字段不变」的比对。 */
    private Map<String, Object> versionRow(long questionId, int version) {
        return jdbcTemplate.queryForMap(
                "SELECT content, correct_answer, analysis, score_default, create_by, create_time, "
                        + "update_by, update_time, deleted_at FROM qb_question_version "
                        + "WHERE question_id = ? AND version = ?", questionId, version);
    }
    /** 与课程侧同构；<b>两侧都断</b>，只写一侧的话端点写死拒绝也能全绿。 */
    @Test
    @DisplayName("⚠ F-114 收窄：题目写操作【仅机构根】—— 分校管理员 403、机构根 200（两侧都断）")
    void questionWriteIsOrgRootOnly() throws Exception {
        assertEquals(403, code(postWithToken(QUESTIONS, loginAs(QuestionFixtures.A1),
                        single("{\"answer\":\"A\"}"))),
                "分校管理员有 question:question:add 权限位，但不是机构根");
        assertEquals(200, code(postWithToken(QUESTIONS, loginAs(QuestionFixtures.ROOT),
                        single("{\"answer\":\"A\"}"))),
                "机构根必须过得去；这里若也 403，说明收窄把机构根一起挡了");
    }

    @Test
    @DisplayName("F-114 收窄【只管写不管读】：分校管理员仍看得见题目列表（组卷要用）")
    void questionReadStillWorksForSubAdmin() throws Exception {
        assertEquals(200, code(getWithToken(QUESTIONS + "?pageSize=10", loginAs(QuestionFixtures.A1))),
                "读接口一个都没动 —— 分校管理员要看得见才能组卷");
    }
}