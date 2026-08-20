package com.edumatrix.org.grant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 接口 40 的两条<b>原地 UPDATE</b> —— 一行都不删。
 *
 * <h2>为什么接口 40 必须独立存在（03-02 §9.4「为什么需要独立接口」逐字）</h2>
 * <p>没有它，续期只能「撤销 + 重新授权」，而<b>撤销强制级联整棵子树</b>
 *（契约 §2.5 规则 5）—— 给一个教师改一下到期日，会<b>连带清空他名下全部学员的授权</b>，
 * 续期反而制造了一次事故。本 Mapper 的两条 SQL 都是 {@code UPDATE ... SET valid_*}，
 * <b>没有一条碰 {@code deleted_at}</b>。
 *
 * <h2>缩短级联、延长不级联（PRD FR-1 规则 4）</h2>
 * <p>这条不对称是<b>刻意的</b>：<b>收紧自动传导，放松需要显式操作</b>。
 * 下级的有效期是上级当初主动收窄的结果，延长上级不应擅自替下级放宽 ——
 * 需要时由下级的直属上级另行调用本接口。
 *
 * <p><b>租户条件由插件注入</b>（契约 §2.9）。
 */
@Mapper
public interface GrantValidityMapper {

    /**
     * 原地更新<b>目标节点自身</b>那一行的有效期。
     *
     * <p>{@code valid_start} / {@code valid_end} 各自可能是「保持原值」，
     * 故用 {@code <if>} 分支 —— 而不是把 {@code null} 一律当成「改为 null」。
     * 两者的区别见 {@code GrantValidityUpdateReq} 的类注释。
     */
    @Update("<script>"
            + "UPDATE org_resource_grant SET update_by = #{operatorId}, update_time = NOW() "
            + "  <if test='startPresent'>, valid_start = #{validStart}</if>"
            + "  <if test='endPresent'>, valid_end = #{validEnd}</if>"
            + " WHERE id = #{grantId} AND deleted_at = 0"
            + "</script>")
    int updateValidity(@Param("grantId") Long grantId,
                       @Param("startPresent") boolean startPresent,
                       @Param("validStart") LocalDateTime validStart,
                       @Param("endPresent") boolean endPresent,
                       @Param("validEnd") LocalDateTime validEnd,
                       @Param("operatorId") Long operatorId);

    /**
     * <b>缩短</b>时把子树内晚于新值的行一并截断（契约 §2.5 规则 7 同源，防时间维度悬挂）。
     *
     * <h2>三个条件各挡一件事</h2>
     * <ul>
     *   <li>{@code n.id <> #{targetNodeId}} —— 目标自身已由 {@link #updateValidity} 单独更新，
     *       重复更新会让 {@code cascadeTruncatedCount} 多数一行；
     *   <li>子树前缀两分支 —— 与级联撤销<b>逐字同源</b>（只写 LIKE 会漏掉整层直接子节点，
     *       LIKE 不以逗号收边会让 {@code ...,100} 误命中 {@code ...,1001}）；
     *   <li>{@code valid_end IS NULL OR valid_end > #{newEnd}} —— <b>只截断确实晚于新值的</b>。
     *       {@code IS NULL} 这一支不能漏：<b>永久有效比任何具体值都晚</b>，
     *       漏了它就会留下「上级 06-30 到期、下级永久有效」的时间维度悬挂，
     *       而那正是这条规则要防的东西。
     * </ul>
     *
     * @return 被连带截断的行数
     */
    @Update("UPDATE org_resource_grant g "
            + "  JOIN org_node n ON n.id = g.target_node_id AND n.deleted_at = 0 "
            + "   SET g.valid_end = #{newEnd}, g.update_by = #{operatorId}, g.update_time = NOW() "
            + " WHERE g.resource_type = #{resourceType} AND g.resource_id = #{resourceId} "
            + "   AND g.deleted_at = 0 AND n.id <> #{targetNodeId} "
            + "   AND (n.ancestors = #{prefix} OR n.ancestors LIKE CONCAT(#{prefix}, ',%')) "
            + "   AND (g.valid_end IS NULL OR g.valid_end > #{newEnd})")
    int truncateSubtree(@Param("resourceType") int resourceType,
                        @Param("resourceId") Long resourceId,
                        @Param("targetNodeId") Long targetNodeId,
                        @Param("prefix") String prefix,
                        @Param("newEnd") LocalDateTime newEnd,
                        @Param("operatorId") Long operatorId);
}
