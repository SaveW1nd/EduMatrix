package com.edumatrix.org.member.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.edumatrix.org.member.entity.OrgStudent;
import com.edumatrix.org.member.mapper.OrgStudentMapper;

/**
 * 删除请求脱敏的执行体（PRD F7-3、契约 §7.2 第 3 条、03-02 §6.9）。
 *
 * <h2>脱敏是<b>覆写掩码</b>，绝不置 NULL</h2>
 * <p>契约 §2.2「同源原则」表第 2 行把这条写死了，理由逐字：
 * 置 NULL 会把「<b>家长本来就没填手机号</b>」和「<b>提过删除请求、已脱敏</b>」
 * 混成同一个状态，<b>而后者恰恰是监管问询时唯一要证明的事</b>。
 * 掩码位保留了可对账性，{@code anonymized_at} 保留了可举证性，两者都不构成个人信息。
 *
 * <p>脱敏的目的是「<b>不能再识别到人</b>」，不是「假装这个人没来过」。
 *
 * <h2>四列覆写 + 一列回填</h2>
 * <table border="1">
 *   <caption>脱敏动作</caption>
 *   <tr><th>列</th><th>动作</th><th>例</th></tr>
 *   <tr><td>{@code org_student.guardian_phone}</td><td>掩码</td><td>{@code 138****5678}</td></tr>
 *   <tr><td>{@code sys_user.phone}</td><td>掩码</td><td>{@code 139****4001}</td></tr>
 *   <tr><td>{@code org_student.guardian_name}</td><td>姓氏 + {@code *}</td><td>{@code 张*}</td></tr>
 *   <tr><td>{@code sys_user.real_name}</td><td>姓氏 + {@code *}</td><td>{@code 李*}</td></tr>
 *   <tr><td>{@code org_student.anonymized_at}</td><td>回填时点</td><td>—</td></tr>
 * </table>
 *
 * <h2>两张日志表<b>一个字都不碰</b></h2>
 * <p>契约 §7.2 第 5 条：{@code sys_login_log} / {@code sys_oper_log} 保留 ≥ 6 个月
 * （《网络安全法》第 21 条），<b>且这两张表不参与「删除请求」的清理</b>。
 * 模块 07 的「禁止事项」也逐字写着「不得让脱敏任务碰两张日志表」。
 * 本类只<b>往</b> {@code sys_oper_log} 写一行留痕（规则 10），<b>不改不删任何既有行</b>。
 *
 * <h2>学习记录同样不删</h2>
 * <p>03-02 §6.9：「学习记录与答卷本身是机构的教学档案，按约定保留期留存——
 * <b>与个人身份标识解绑后不再构成个人信息</b>」。本类因此只碰 {@code org_student}
 * 与 {@code sys_user} 两张表的四列。
 */
@Service
public class StudentAnonymizeService {

    /** 手机号掩码：保留前 3 位与后 4 位（契约 §7.2「{@code 138****5678}」）。 */
    private static final String PHONE_MASK_SUFFIX = "****";

    /** 姓名掩码：保留姓氏，其余一律一个 {@code *}。 */
    private static final String NAME_MASK = "*";

    private final OrgStudentMapper studentMapper;
    private final com.edumatrix.org.node.mapper.NodeAccountMapper accountMapper;
    private final MemberOperLogWriter operLogWriter;

    public StudentAnonymizeService(OrgStudentMapper studentMapper,
                                   com.edumatrix.org.node.mapper.NodeAccountMapper accountMapper,
                                   MemberOperLogWriter operLogWriter) {
        this.studentMapper = studentMapper;
        this.accountMapper = accountMapper;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 租户清单：脱敏任务逐租户进入的驱动列表。
     *
     * <p>{@code sys_tenant} 不带 {@code tenant_id} 列、压根不进租户插件，
     * 因此本方法<b>不需要任何逃生舱</b> —— 见 {@code OrgStudentMapper#selectActiveTenantIds}。
     */
    public List<Long> findActiveTenantIds() {
        return studentMapper.selectActiveTenantIds();
    }

    /** 扫描候选。三个与门的 SQL 见 {@code OrgStudentMapper#selectAnonymizeCandidates}。 */
    public List<OrgStudent> findCandidates(LocalDateTime deadline, int limit) {
        return studentMapper.selectAnonymizeCandidates(deadline, limit);
    }

    /**
     * 对一名学员执行脱敏。<b>一人一个事务</b>：四列覆写 + 回填 + 留痕要么全成、要么全不成。
     *
     * <p><b>回填 {@code anonymized_at} 与覆写必须同事务</b>：只覆写不回填，
     * 下次调度会再脱一次（此时读到的已是掩码值，会把 {@code 138****5678} 再掩码一遍）；
     * 只回填不覆写，则原值留在库里而系统认为已经删了 —— 后者是一次<b>合规事故</b>。
     */
    @Transactional(rollbackFor = Exception.class)
    public void anonymize(OrgStudent student) {
        LocalDateTime now = LocalDateTime.now();

        studentMapper.anonymize(student.getId(),
                maskName(student.getGuardianName()),
                maskPhone(student.getGuardianPhone()),
                now);

        // sys_user 那半边：real_name 与 phone。用 org 领域已有的窄入口，
        // 不为脱敏另开第二个 sys_user Mapper（见 NodeAccountMapper 类注释）
        accountMapper.anonymizeAccount(student.getUserId());

        // 规则 10：记 sys_oper_log。tenant_id 由 Job 用 runWithTenant 提供，
        // 这里从数据行再取一次是为了不依赖线程上下文（契约 §2.8 规则 1）
        operLogWriter.anonymized(student.getId(), student.getTenantId());
    }

    /**
     * 手机号掩码 {@code 138****5678}。
     *
     * <p><b>{@code null} 原样返回 {@code null}</b> —— 这不是「置 NULL 脱敏」，
     * 恰恰相反：本来就没填的字段<b>保持没填</b>，而填过的被覆写成掩码。
     * 两者因此仍然可区分，正是同源原则要的效果
     * （区分「提没提过删除请求」靠的是 {@code anonymized_at}，不是这一列）。
     *
     * <p>长度不足 7 位时整体掩码 —— 不能靠截取，那会漏出真实数字。
     */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        if (phone.length() < 7) {
            return PHONE_MASK_SUFFIX;
        }
        return phone.substring(0, 3) + PHONE_MASK_SUFFIX + phone.substring(phone.length() - 4);
    }

    /**
     * 姓名掩码「姓氏 + {@code *}」。
     *
     * <p><b>只保留一个字</b>，且无论原名多长都只补一个 {@code *}：
     * 补成与原名等长（{@code 欧阳**}）会泄露姓名长度，而姓名长度在小样本里是可识别信息。
     */
    static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        return name.substring(0, 1) + NAME_MASK;
    }
}
