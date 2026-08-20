package com.edumatrix.org.grant.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.edumatrix.common.operlog.OperLogWriter;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.grant.vo.GrantRevokedVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 模块 11 的领域侧留痕 —— 与 {@code org/member/service/MemberOperLogWriter} <b>同型</b>。
 *
 * <h2>为什么切面不够，必须再写一条</h2>
 * <p>{@code common/operlog/OperLogAspect} 序列化的是<b>入参</b>。撤销的入参是
 * {@code resourceIds} / {@code targetNodeIds} —— <b>里面没有任何影响面数字</b>。
 * 而 04 §B 模块 11 规则 17 与 PRD FR-4 规则 7 逐字要求：
 * 「撤销全量写 {@code sys_oper_log}，<b>含级联影响的节点数与学员数</b>」。
 *
 * <p>那两个数字只在<b>返回值</b>里，而切面看不到返回值。
 * <b>「响应里有」和「日志里有」在合规上是两回事</b>：响应不留痕，
 * 一次撤销影响了 9 个节点 8 名学员这件事，事后<b>无从查证</b> ——
 * 而 {@code sys_oper_log} 按契约 §7.2 第 5 条要保留 ≥6 个月，
 * 是<b>排查越权时唯一的原始事实</b>。
 *
 * <h2>为什么是「直调 {@code OperLogWriter}」而不是改切面</h2>
 * <p>改切面（让它也序列化返回值 / 加一条结果通道）会影响<b>全部 44 个写端点</b>的
 * {@code params} 列体积与语义。而直调的爆炸半径是零：不动切面、不动其他端点、
 * 不改 {@code @OperLog} 的语义，写的还是<b>同一张表、同一个写入器</b> ——
 * 切面内部用的也是 {@link OperLogWriter}，直调只是绕过「从入参序列化」这一步。
 * <b>这不是第二份实现。</b>
 *
 * <p>本项目<b>已有三处先例</b>：{@code OrgStudentService#guardianConsent}（那个端点
 * 同样标着 {@code @OperLog}，于是一次请求两条日志、各记一个事实）、
 * {@code StudentAnonymizeService#anonymized}、模块 09 的孤儿处置（它根本没有 HTTP 上下文）。
 *
 * <h2>一次撤销两条日志，是「一行一个事实」不是「重复记账」</h2>
 * <table border="1">
 *   <caption>两行各回答一个问题</caption>
 *   <tr><th>写入者</th><th>{@code action}</th><th>回答</th></tr>
 *   <tr><td>切面</td><td>{@link #ACTION_REVOKE}</td>
 *       <td>谁、什么时候、从哪个 IP、撤了哪些资源（入参）、耗时多久、成功还是失败</td></tr>
 *   <tr><td>本类</td><td>{@link #ACTION_REVOKE_IMPACT}</td>
 *       <td><b>这次撤销实际影响了谁</b>：行数 / 节点数 / 学员数</td></tr>
 * </table>
 * <p>合并成一条做不到 —— 切面在 {@code finally} 里写，那时它拿不到返回值；
 * 而本类在事务内写，那时 {@code ip} / {@code cost_ms} 还不知道。
 * 两者<b>各自只写自己确实知道的东西</b>，比拼一条半真半假的强。
 */
@Service
public class GrantOperLogWriter {

    /**
     * {@code sys_oper_log.module}。
     *
     * <p><b>切面与本类共用这一个常量</b>（{@code OrgGrantController} 的 {@code @OperLog}
     * 直接引用它）—— 字典只有一份，不会出现「切面写『资源授权』、手写那条写『授权管理』」
     * 这种事后没人能按 module 把两行关联起来的情况。
     */
    public static final String MODULE_GRANT = "资源授权";

    /** 接口 38（切面写）。 */
    public static final String ACTION_GRANT = "授权资源给节点";

    /** 接口 39（切面写）。 */
    public static final String ACTION_REVOKE = "撤销资源授权（级联子树）";

    /** 接口 40（切面写）。 */
    public static final String ACTION_EDIT_VALIDITY = "修改授权有效期";

    /** 接口 39 的影响面（<b>本类写</b>）—— 规则 17 要的那两个数字在这一行。 */
    public static final String ACTION_REVOKE_IMPACT = "撤销影响面留痕";

    private final OperLogWriter operLogWriter;
    private final ObjectMapper objectMapper;

    public GrantOperLogWriter(OperLogWriter operLogWriter, ObjectMapper objectMapper) {
        this.operLogWriter = operLogWriter;
        this.objectMapper = objectMapper;
    }

    /**
     * 记一次撤销的影响面。<b>在撤销的同一事务内调用</b>。
     *
     * <h2>用 {@code writeOrThrow}，写不进去就让撤销一起回滚</h2>
     * <p>与 {@code MemberOperLogWriter#guardianConsent} 同一条推理，方向相反但同源：
     * <b>撤销落了库、而它唯一的影响面记录静默丢失</b>，比这次撤销失败更糟 ——
     * 操作者可以重试一次撤销，但没有人能把「那次撤销当时影响了谁」重建出来。
     * 而这恰恰是事后排查越权时要看的那一行。
     *
     * <p>{@code oper_time} 由 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP} 赋值 ——
     * 只认数据库这一个时钟。{@code ip} / {@code cost_ms} 留空：那是切面的职责，
     * 本类不知道也不猜（{@code MemberOperLogWriter} 同口径）。
     */
    public void revokeImpact(GrantRevokedVO vo) {
        operLogWriter.writeOrThrow(TenantHelper.getUserId(),
                MODULE_GRANT, ACTION_REVOKE_IMPACT,
                "DELETE /api/v1/org/grants#impact",
                impactJson(vo), null, OperLogWriter.STATUS_SUCCESS, null, 0, null);
    }

    /**
     * 影响面 → JSON。
     *
     * <p>用 {@link ObjectMapper} 而不是手拼字符串：<b>今天</b>这些值全是整数、手拼确实安全，
     * 但下一个往里加 {@code reason}（用户可输入的 500 字）的人不会回来补转义 ——
     * 那时落进 {@code params} 的就是一段坏掉的 JSON，而<b>没有任何东西会报错</b>，
     * 只是排查越权时那一行解析不出来。
     */
    private String impactJson(GrantRevokedVO vo) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("resourceType", vo.getResourceType());
        impact.put("resourceCount", vo.getResourceCount());
        impact.put("targetNodeCount", vo.getTargetNodeCount());
        impact.put("revokedCount", vo.getRevokedCount());
        impact.put("directRevokedCount", vo.getDirectRevokedCount());
        impact.put("cascadeRevokedCount", vo.getCascadeRevokedCount());
        impact.put("affectedNodeCount", vo.getAffectedNodeCount());
        impact.put("affectedStudentCount", vo.getAffectedStudentCount());
        try {
            return objectMapper.writeValueAsString(impact);
        } catch (JsonProcessingException e) {
            // 序列化不出来也不能让撤销静默丢掉影响面：退回一行可读文本，
            // 【绝不返回 null】—— null 的表现与「这次撤销没有影响面」完全一样
            return "序列化失败，影响面：revokedCount=" + vo.getRevokedCount()
                    + " affectedNodeCount=" + vo.getAffectedNodeCount()
                    + " affectedStudentCount=" + vo.getAffectedStudentCount();
        }
    }
}
