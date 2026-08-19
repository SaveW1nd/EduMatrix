package com.edumatrix.question.bank.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.edumatrix.question.bank.vo.QuestionListVO;

/**
 * 接口 5 分页查询题目的专用 Mapper —— <b>只读</b>。
 *
 * <h2>为什么不用 MyBatis-Plus 的 LambdaQuery 拼</h2>
 * <p>响应行要 {@code scoreDefault}（在版本表）、{@code categoryName}（在分类表），
 * 逐行点查是 N 次往返。一条 JOIN 一次拿完。
 *
 * <h2>可见性谓词写在这里，与 QuestionVisibilityChecker 是【同两个输入】</h2>
 * <p>{@code owner_node_id = #{myNodeId} OR id IN (#{grantedIds})} ——
 * 不调 {@code visibleIds()} 是因为那会把上千个自有题 ID 塞进 {@code IN}。
 * 两处用的是同一个 {@code ResourceGrantReader} 给出的授权 ID 集，
 * 是一条谓词的两种写法，<b>不是两份实现</b>。这一点在
 * {@code QuestionVisibilityChecker#visibleIds} 的注释里也写了。
 *
 * <p><b>租户条件由插件注入</b>，这里一个字不写（契约 §2.9）。
 * {@code deleted_at = 0} 因为是手写 SQL、不走 {@code @TableLogic}，必须显式写。
 */
@Mapper
public interface QuestionPageMapper {

    /**
     * @param grantedIds 被授权的题目 ID；<b>可能为空</b>，SQL 里已按空集处理
     *                   （空 {@code IN ()} 是语法错误）
     * @param grantType  1 仅自有 2 仅被授权；{@code null} 返回并集
     */
    @Select("""
            <script>
            SELECT q.id, q.category_id AS categoryId, c.category_name AS categoryName,
                   q.question_type AS questionType, q.difficulty,
                   q.current_version AS currentVersion, q.stem_preview AS stemPreview,
                   v.score_default AS scoreDefault, q.status,
                   q.owner_node_id AS ownerNodeId,
                   CASE WHEN q.owner_node_id = #{myNodeId} THEN 1 ELSE 2 END AS grantType,
                   q.create_by AS creatorId, q.create_time AS createTime, q.update_time AS updateTime
              FROM qb_question q
              LEFT JOIN qb_category c ON c.id = q.category_id AND c.deleted_at = 0
              LEFT JOIN qb_question_version v
                     ON v.question_id = q.id AND v.version = q.current_version AND v.deleted_at = 0
             WHERE q.deleted_at = 0
               AND q.parent_id = 0
               <choose>
                 <when test="grantType != null and grantType == 1">
                   AND q.owner_node_id = #{myNodeId}
                 </when>
                 <when test="grantType != null and grantType == 2">
                   <choose>
                     <when test="grantedIds != null and grantedIds.size() > 0">
                       AND q.owner_node_id &lt;&gt; #{myNodeId}
                       AND q.id IN
                       <foreach collection="grantedIds" item="g" open="(" separator="," close=")">#{g}</foreach>
                     </when>
                     <otherwise>AND 1 = 0</otherwise>
                   </choose>
                 </when>
                 <otherwise>
                   AND (q.owner_node_id = #{myNodeId}
                   <if test="grantedIds != null and grantedIds.size() > 0">
                     OR q.id IN
                     <foreach collection="grantedIds" item="g" open="(" separator="," close=")">#{g}</foreach>
                   </if>
                   )
                 </otherwise>
               </choose>
               <if test="questionType != null">AND q.question_type = #{questionType}</if>
               <if test="difficulty != null">AND q.difficulty = #{difficulty}</if>
               <if test="status != null">AND q.status = #{status}</if>
               <if test="creatorId != null">AND q.create_by = #{creatorId}</if>
               <if test="keyword != null and keyword != ''">
                 AND q.stem_preview LIKE CONCAT('%', #{keyword}, '%')
               </if>
               <if test="categoryIds != null and categoryIds.size() > 0">
                 AND q.category_id IN
                 <foreach collection="categoryIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
               </if>
             ORDER BY q.create_time DESC, q.id DESC
            </script>
            """)
    IPage<QuestionListVO> selectVisiblePage(IPage<QuestionListVO> page,
                                            @Param("myNodeId") Long myNodeId,
                                            @Param("grantedIds") List<Long> grantedIds,
                                            @Param("grantType") Integer grantType,
                                            @Param("questionType") Integer questionType,
                                            @Param("difficulty") Integer difficulty,
                                            @Param("status") Integer status,
                                            @Param("creatorId") Long creatorId,
                                            @Param("keyword") String keyword,
                                            @Param("categoryIds") List<Long> categoryIds);
}
