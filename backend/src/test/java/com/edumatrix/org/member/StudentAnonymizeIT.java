package com.edumatrix.org.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.org.member.job.AnonymizeArchivedStudentJob;
import com.edumatrix.org.member.service.MemberOperLogWriter;
import com.edumatrix.org.member.support.MemberFixtures;
import com.edumatrix.org.member.support.MemberIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD F7-3 删除请求：归档 + 30 日不可逆脱敏。
 *
 * <h2>本类的核心是<b>两条路的对照</b>，缺一条这个模块就是错的</h2>
 * <p>{@link #deletionRequestIsAnonymizedAfterThirtyDays} 与
 * {@link #graduatedStudentIsNotAnonymizedAfterThirtyDays} <b>必须同时存在</b>：
 * 只测前者的话，扫描条件里少写一个 {@code archive_reason = 2} <b>不会被发现</b>，
 * 而那个缺陷的后果是<b>把毕业校友的联系方式一并抹掉，且不可逆</b>。
 */
class StudentAnonymizeIT extends MemberIntegrationTestBase {

    @Autowired
    private AnonymizeArchivedStudentJob anonymizeJob;

    /** 用第 1 名学员做「因删除请求归档」，第 2 名做「正常毕业」的对照。 */
    private static final long DELETION_REQUEST_STUDENT = MemberFixtures.S_BASE;
    private static final long GRADUATED_STUDENT = MemberFixtures.S_BASE + 1;

    @Test
    @DisplayName("F7-3：archiveReason=2 归档满 30 日 → 脱敏（掩码覆写，不是置 NULL）")
    void deletionRequestIsAnonymizedAfterThirtyDays() {
        long profileId = MemberFixtures.profileIdOf(DELETION_REQUEST_STUDENT);
        memberFixtures.archiveDaysAgo(profileId, 2, 31);

        String phoneBefore = memberFixtures.studentGuardianPhone(profileId);
        assertThat(phoneBefore).hasSize(11);

        int done = anonymizeJob.run();
        assertThat(done).isEqualTo(1);

        // guardian_phone 覆写为掩码 —— 【保留掩码位，绝不置 NULL】（契约 §2.2 同源原则表第 2 行）
        String phoneAfter = memberFixtures.studentGuardianPhone(profileId);
        assertThat(phoneAfter).isNotNull();
        assertThat(phoneAfter).isEqualTo(
                phoneBefore.substring(0, 3) + "****" + phoneBefore.substring(7));

        // guardian_name 覆写为姓氏 + *
        assertThat(memberFixtures.studentGuardianName(profileId)).endsWith("*").hasSize(2);

        // sys_user 那半边：real_name 与 phone
        assertThat(memberFixtures.userRealName(DELETION_REQUEST_STUDENT)).endsWith("*");
        assertThat(memberFixtures.userPhone(DELETION_REQUEST_STUDENT)).contains("****");

        // 回填 anonymized_at —— 「这个人提没提过删除请求」的唯一证据
        assertThat(memberFixtures.studentAnonymizedAt(profileId)).isNotNull();

        // 规则 10：记 sys_oper_log
        assertThat(memberFixtures.operLogCount(MemberOperLogWriter.ACTION_ANONYMIZE)).isEqualTo(1);
    }

    @Test
    @DisplayName("F7-3 对照：archiveReason=1 正常毕业满 30 日 → 不脱敏，联系方式原样保留")
    void graduatedStudentIsNotAnonymizedAfterThirtyDays() {
        long profileId = MemberFixtures.profileIdOf(GRADUATED_STUDENT);
        memberFixtures.archiveDaysAgo(profileId, 1, 31);

        String nameBefore = memberFixtures.studentGuardianName(profileId);
        String phoneBefore = memberFixtures.studentGuardianPhone(profileId);

        int done = anonymizeJob.run();

        // 【本条是扫描条件三个与门的第一个】少写 archive_reason = 2 时，
        // 上面那条用例照样绿，只有这一条会红
        assertThat(done).as("正常毕业的校友不该被脱敏").isZero();
        assertThat(memberFixtures.studentGuardianName(profileId)).isEqualTo(nameBefore);
        assertThat(memberFixtures.studentGuardianPhone(profileId)).isEqualTo(phoneBefore);
        assertThat(memberFixtures.studentAnonymizedAt(profileId)).isNull();
        assertThat(memberFixtures.userPhone(GRADUATED_STUDENT))
                .isEqualTo(MemberFixtures.phoneOf(GRADUATED_STUDENT));
    }

    @Test
    @DisplayName("F7-3：30 日撤回窗口内不脱敏（第二个与门）")
    void withinRecallWindowIsNotAnonymized() {
        long profileId = MemberFixtures.profileIdOf(DELETION_REQUEST_STUDENT);
        memberFixtures.archiveDaysAgo(profileId, 2, 29);

        assertThat(anonymizeJob.run()).isZero();
        assertThat(memberFixtures.studentAnonymizedAt(profileId)).isNull();
    }

    @Test
    @DisplayName("F7-3：重复执行不二次脱敏（第三个与门 anonymized_at IS NULL）")
    void alreadyAnonymizedIsSkipped() {
        long profileId = MemberFixtures.profileIdOf(DELETION_REQUEST_STUDENT);
        memberFixtures.archiveDaysAgo(profileId, 2, 31);

        assertThat(anonymizeJob.run()).isEqualTo(1);
        String maskedPhone = memberFixtures.studentGuardianPhone(profileId);

        // 第二次跑：不该再命中，也不该把掩码值再掩码一遍（138****5678 → 138****5678）
        assertThat(anonymizeJob.run()).isZero();
        assertThat(memberFixtures.studentGuardianPhone(profileId)).isEqualTo(maskedPhone);
        assertThat(memberFixtures.operLogCount(MemberOperLogWriter.ACTION_ANONYMIZE)).isEqualTo(1);
    }

    @Test
    @DisplayName("契约 §7.2 第 5 条：脱敏不碰 sys_login_log / sys_oper_log 的既有行")
    void anonymizeDoesNotTouchLogTables() {
        long profileId = MemberFixtures.profileIdOf(DELETION_REQUEST_STUDENT);
        memberFixtures.seedLoginLog(DELETION_REQUEST_STUDENT);
        memberFixtures.archiveDaysAgo(profileId, 2, 31);

        int loginLogsBefore = memberFixtures.loginLogTotal();
        int operLogsBefore = memberFixtures.operLogTotal();
        assertThat(loginLogsBefore).isPositive();

        anonymizeJob.run();

        // 两张日志表保留 ≥ 6 个月（《网络安全法》第 21 条），不参与删除请求的清理。
        // sys_oper_log 只【多】一行留痕，既有行一条都不能少
        assertThat(memberFixtures.loginLogTotal()).isEqualTo(loginLogsBefore);
        assertThat(memberFixtures.operLogTotal()).isEqualTo(operLogsBefore + 1);

        // 登录日志里的用户名【原样保留】—— 那是审计线索，不是个人信息载体
        Integer stillThere = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_login_log WHERE username = ?",
                Integer.class, MemberFixtures.usernameOf(DELETION_REQUEST_STUDENT));
        assertThat(stillThere).isEqualTo(1);
    }

    @Test
    @DisplayName("F7-3：已脱敏不可归档恢复 → 10209（不是 400）")
    void anonymizedStudentCannotBeUnarchived() throws Exception {
        long profileId = MemberFixtures.profileIdOf(DELETION_REQUEST_STUDENT);
        memberFixtures.archiveDaysAgo(profileId, 2, 31);
        anonymizeJob.run();

        String token = loginAsRoot();
        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + profileId + "/unarchive", token, "{}");

        // DDL 对 anonymized_at 的列注释写的是 400，另外七处写 10209 —— 已登记为 F-28。
        // 实现取 10209：参数完全合法、是对象状态不允许，与 10204 同形
        assertThat(code(response)).isEqualTo(10209);
        assertThat(memberFixtures.studentStatus(profileId)).isEqualTo(2);
    }

    @Test
    @DisplayName("F7-3：30 日窗口内可以恢复（这正是撤回窗口的意义）")
    void withinRecallWindowCanBeUnarchived() throws Exception {
        long profileId = MemberFixtures.profileIdOf(DELETION_REQUEST_STUDENT);
        memberFixtures.archiveDaysAgo(profileId, 2, 10);

        String token = loginAsRoot();
        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + profileId + "/unarchive", token, "{}");

        assertThat(code(response)).isEqualTo(200);
        assertThat(memberFixtures.studentStatus(profileId)).isZero();
        // 【恢复必须清空 archive_reason】否则这名已复课的学员会在原归档日满 30 日时被脱敏
        assertThat(memberFixtures.studentArchiveReason(profileId)).isNull();

        assertThat(anonymizeJob.run()).as("已恢复的学员不该再被脱敏任务扫到").isZero();
    }
}
