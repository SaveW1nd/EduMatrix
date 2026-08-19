package com.edumatrix.question.bank.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.edumatrix.question.bank.entity.QbQuestionVersion;

/**
 * {@code qb_question_version} —— <b>只增不改的窄 Mapper</b>。
 *
 * <h2>它【不 extends BaseMapper】，这是本模块最硬的一道守卫</h2>
 * <p>契约 §4 版本规则与 PRD F3-2 规则 3：「历史版本不可修改、不可删除」
 * 「无任何更新入口（含管理员）」。落实方式<b>不是"我们不会那么写"</b>——
 * 继承 {@code BaseMapper} 会白送 {@code updateById} / {@code update} /
 * {@code deleteById} / {@code delete} 四个方法，那时「不改历史版本」就只剩自觉。
 * 不继承之后，{@code versionMapper.updateById(row)} <b>编译不过</b>：方法不存在。
 *
 * <p>代价是 {@code insert} 要自己写，且 MyBatis-Plus 的
 * {@code AuditFieldHandler}（只作用于 MP 自己的插入方法）不再自动填
 * {@code id} / {@code create_by} / {@code update_by} —— 由
 * {@code QuestionVersionProvider} 显式赋值。这点手工成本换一条编译期护栏，值。
 *
 * <p><b>租户列仍由插件注入</b>（契约 §2.9）：{@code TenantLineInnerInterceptor}
 * 处理 INSERT 与 SELECT，本接口一个 {@code tenant_id} 都不写。
 *
 * <p>另外两道：{@code scripts/check_backend_conventions.sh} 检查 ⑦ 防复发；
 * 库级触发器<b>不加</b>（F-73 定案，理由见 {@link QbQuestionVersion} 类注释）。
 */
@Mapper
public interface QbQuestionVersionMapper {

    /**
     * 追加一条版本快照。{@code id} / {@code create_by} / {@code update_by} 由调用方填，
     * {@code tenant_id} 由租户插件注入，两个时间列由数据库默认值赋值。
     */
    @Insert("INSERT INTO qb_question_version "
            + "(id, question_id, version, content, correct_answer, analysis, score_default, "
            + " create_by, update_by, deleted_at) "
            + "VALUES (#{id}, #{questionId}, #{version}, #{content}, #{correctAnswer}, "
            + " #{analysis}, #{scoreDefault}, #{createBy}, #{updateBy}, 0)")
    int append(QbQuestionVersion row);

    /** 读某个不可变快照；无行返回 {@code null}（调用方按 30007 处置）。 */
    @Select("SELECT * FROM qb_question_version "
            + "WHERE question_id = #{questionId} AND version = #{version} AND deleted_at = 0")
    QbQuestionVersion selectSnapshot(@Param("questionId") Long questionId,
                                     @Param("version") Integer version);

    /** 按 {@code version} 倒序返回全部版本（03-04 §2.5：版本数量有限，不分页）。 */
    @Select("SELECT * FROM qb_question_version "
            + "WHERE question_id = #{questionId} AND deleted_at = 0 ORDER BY version DESC")
    List<QbQuestionVersion> selectAllByQuestion(@Param("questionId") Long questionId);

    /** 当前最大版本号；无行返回 {@code null}。用于并发下的版本号推进（配合主表行锁）。 */
    @Select("SELECT MAX(version) FROM qb_question_version "
            + "WHERE question_id = #{questionId} AND deleted_at = 0")
    Integer selectMaxVersion(@Param("questionId") Long questionId);
}
