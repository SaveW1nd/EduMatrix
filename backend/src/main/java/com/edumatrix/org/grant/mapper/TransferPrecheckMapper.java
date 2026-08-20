package com.edumatrix.org.grant.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.edumatrix.common.grant.mapper.ResourceGrantMapper;

/**
 * 接口 52 的两条只读查询（03-02 §6.12）。
 *
 * <p><b>本接口只读，一个字都不写</b>：预检就是「把影响面在执行前摊开给操作者」，
 * 处置动作复用接口 38 / 39。为它开一个写接口会造出第三套授权语义。
 *
 * <p><b>租户条件由插件注入</b>（契约 §2.9）。
 */
@Mapper
public interface TransferPrecheckMapper {

    /**
     * 学生档案 ID → 节点信息。
     *
     * <p>接口 52 的入参是 {@code org_student.id}（学籍档案），而授权挂在
     * {@code org_node.id} 上 —— <b>这两个 ID 不是一回事</b>，混用的表现是
     * 「预检说没有影响面」而实际有一片，且接口返回 200。
     */
    @Select("<script>"
            + "SELECT s.id AS studentId, s.node_id AS nodeId, n.node_name AS realName, "
            + "       n.ancestors AS ancestors "
            + "  FROM org_student s "
            + "  JOIN org_node n ON n.id = s.node_id AND n.deleted_at = 0 "
            + " WHERE s.deleted_at = 0 AND s.id IN "
            + "<foreach collection='studentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<StudentNodeRow> selectStudentNodes(@Param("studentIds") List<Long> studentIds);

    /**
     * 这些<b>学生节点</b>当前持有的全部有效授权。
     *
     * <p><b>有效期谓词直接复用 {@link ResourceGrantMapper#VALID_NOW}</b>，不抄一份。
     *
     * <p>抄一份还会撞上那个常量注释里写明的坑：它<b>刻意写成 {@code NOW() >= valid_start}
     * 而不是 {@code valid_start <= NOW()}</b>，因为拼进 {@code <script>} 的 SQL 要按 XML 解析，
     * <b>裸的 {@code <} 会让 MyBatis 在启动时抛
     * {@code SAXParseException: 元素内容必须由格式正确的字符数据或标记组成}</b>。
     * 我第一版就是自己写了一遍 {@code valid_start <= NOW()}，
     * 表现是<b>整个 Spring 上下文起不来</b> —— 响亮失败，但完全可以不发生。
     */
    @Select("<script>"
            + "SELECT resource_type AS resourceType, resource_id AS resourceId, "
            + "       target_node_id AS targetNodeId "
            + "  FROM org_resource_grant "
            + " WHERE target_node_id IN "
            + "<foreach collection='nodeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + ResourceGrantMapper.VALID_NOW
            + "</script>")
    List<HeldGrantRow> selectHeldGrants(@Param("nodeIds") List<Long> nodeIds);

    /** {@link #selectStudentNodes} 的行。 */
    class StudentNodeRow {
        private Long studentId;
        private Long nodeId;
        private String realName;
        private String ancestors;

        public Long getStudentId() {
            return studentId;
        }

        public void setStudentId(Long studentId) {
            this.studentId = studentId;
        }

        public Long getNodeId() {
            return nodeId;
        }

        public void setNodeId(Long nodeId) {
            this.nodeId = nodeId;
        }

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public String getAncestors() {
            return ancestors;
        }

        public void setAncestors(String ancestors) {
            this.ancestors = ancestors;
        }
    }

    /** {@link #selectHeldGrants} 的行。 */
    class HeldGrantRow {
        private Integer resourceType;
        private Long resourceId;
        private Long targetNodeId;

        public Integer getResourceType() {
            return resourceType;
        }

        public void setResourceType(Integer resourceType) {
            this.resourceType = resourceType;
        }

        public Long getResourceId() {
            return resourceId;
        }

        public void setResourceId(Long resourceId) {
            this.resourceId = resourceId;
        }

        public Long getTargetNodeId() {
            return targetNodeId;
        }

        public void setTargetNodeId(Long targetNodeId) {
            this.targetNodeId = targetNodeId;
        }
    }
}
