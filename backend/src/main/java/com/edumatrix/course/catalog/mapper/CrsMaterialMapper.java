package com.edumatrix.course.catalog.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.course.catalog.entity.CrsMaterial;

/** {@code crs_material}。租户条件由插件注入（契约 §2.9）。 */
@Mapper
public interface CrsMaterialMapper extends BaseMapper<CrsMaterial> {

    /**
     * 按<b>附件文件 ID</b> 反查：这份附件属于哪个图文资料、挂在哪门课下。
     *
     * <p>{@code material_attach} 的归属校验（03-01 §7.3、模块 05 的 B-3 / F-38）要它。
     * {@code crs_material.attachment_file_ids} 是 JSON 数组（元素是<b>字符串形态</b>的
     * {@code sys_file.id}），故用 {@code JSON_CONTAINS(..., JSON_QUOTE(CAST(? AS CHAR)))}
     * —— 不能拿 {@code LIKE '%id%'} 凑：{@code 123} 会命中 {@code 1234}。
     *
     * <p><b>LEFT JOIN 课时</b>：图文资料<b>可以还没被任何课时引用</b>（先建资料后编排）。
     * 那时 {@code courseId} 为 {@code null}，判定只剩「管理端」那一支 —— 这是对的，
     * 用 INNER JOIN 会让「刚上传、还没挂课时」的附件对<b>它的创建者自己</b>也 404。
     *
     * <p><b>租户条件由插件注入</b>（契约 §2.9）。
     *
     * @return 每行 = (资料归属节点, 引用它的课程 ID)；一个资料被多个课时引用时多行
     */
    @Select("SELECT m.owner_node_id AS ownerNodeId, l.course_id AS courseId "
            + "  FROM crs_material m "
            + "  LEFT JOIN crs_lesson l ON l.content_id = m.id AND l.lesson_type = 2 "
            + "       AND l.deleted_at = 0 "
            + " WHERE m.deleted_at = 0 "
            + "   AND JSON_CONTAINS(m.attachment_file_ids, JSON_QUOTE(CAST(#{fileId} AS CHAR)))")
    List<MaterialAttachRef> selectRefsByAttachmentFileId(@Param("fileId") Long fileId);

    /** {@link #selectRefsByAttachmentFileId} 的行。 */
    class MaterialAttachRef {
        private Long ownerNodeId;
        /** 引用该资料的课程；资料尚未被任何课时引用时为 {@code null}。 */
        private Long courseId;

        public Long getOwnerNodeId() {
            return ownerNodeId;
        }

        public void setOwnerNodeId(Long ownerNodeId) {
            this.ownerNodeId = ownerNodeId;
        }

        public Long getCourseId() {
            return courseId;
        }

        public void setCourseId(Long courseId) {
            this.courseId = courseId;
        }
    }
}
