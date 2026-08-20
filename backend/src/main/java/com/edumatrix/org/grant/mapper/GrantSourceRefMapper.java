package com.edumatrix.org.grant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code source_ref_id} 的名称解析 —— 两条<b>窄只读</b>。
 *
 * <p>03-02 §9.5 的响应行有 {@code sourceRefName}（来源对象名称）。来源按
 * {@code grant_source} 分四种：节点（{@code =2} / {@code =4}）、标签（{@code =3}）、
 * 模板（{@code =5}），{@code =1} 手动选择无来源对象。
 * 节点名走 {@code common/subtree/NodeNameReader}（已有）；标签与模板落在这里。
 *
 * <h2>为什么现在就实现，而不是先回 {@code null}</h2>
 * <p>{@code org_tag} 与 {@code org_perm_template} 是模块 01 基线 DDL 里<b>已经存在</b>的表
 *（契约 §4 的 41 张之二），只是它们的<b>接口</b>在模块 17。
 * 接口 38 从第一天起就接受 {@code grantSource ∈ {1..5}}（那是调用方给的审计标记），
 * 所以 {@code =3} / {@code =5} 的行<b>现在就可能被写进来</b>。
 * 先回 {@code null} 会留下一个「字段在、值恒空」的洞 —— 而那正是模块 06 给
 * {@code resourceName} 留下、需要本模块回头补的那种洞。
 *
 * <p><b>本类永远只读</b>：这两张表的写侧属于模块 17。出现 {@code @Insert} /
 * {@code @Update} / {@code @Delete} 即为越界。
 *
 * <p><b>租户条件由插件注入</b>，一个字不写（契约 §2.9）。
 */
@Mapper
public interface GrantSourceRefMapper {

    /** 标签名（{@code grant_source = 3}）。查不到返回 {@code null}。 */
    @Select("SELECT tag_name FROM org_tag WHERE id = #{tagId} AND deleted_at = 0")
    String selectTagName(@Param("tagId") Long tagId);

    /** 模板名（{@code grant_source = 5}）。查不到返回 {@code null}。 */
    @Select("SELECT template_name FROM org_perm_template WHERE id = #{templateId} AND deleted_at = 0")
    String selectTemplateName(@Param("templateId") Long templateId);
}
