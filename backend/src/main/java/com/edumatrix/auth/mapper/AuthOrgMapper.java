package com.edumatrix.auth.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 登录校验与 {@code /auth/me} 要读的组织侧三张表：
 * {@code sys_tenant}（{@code 10007}）、{@code org_student}（{@code 10015}）、
 * {@code org_node}（节点名与面包屑）。
 *
 * <p>组织侧的<b>停用判定</b>不在这里 —— 它在 {@code common/frozen/FrozenNodeMapper}，
 * 与每请求鉴权的降级查库共用同一条 SQL。规则只有一处定义，登录与鉴权不可能判出不同结果。
 */
@Mapper
public interface AuthOrgMapper {

    /**
     * 取租户状态与到期时间（{@code 10007} 的依据，PRD F1-1 规则 3/4）。
     *
     * <p>{@code sys_tenant} 没有 {@code tenant_id} 列，是租户插件的
     * {@code ignoreTable} 名单里那两张之一（契约 §2.9），因此无需任何租户上下文。
     */
    @Select("SELECT id, name, root_node_id AS rootNodeId, expire_time AS expireTime, status "
            + "FROM sys_tenant WHERE id = #{tenantId} AND deleted_at = 0")
    TenantRow selectTenant(@Param("tenantId") Long tenantId);

    /**
     * 取学籍状态（{@code 10015} 的依据）。
     *
     * <p>只有 {@code status = 2}（毕业归档）拒登 —— 00-通用约定 §9.2 对 {@code 10015}
     * 的定义逐字是「学生学籍 {@code org_student.status=2}（毕业归档）后登录被拒」。
     * {@code status = 1}（已退课）不在其中，本模块不擅自扩大。
     */
    @Select("SELECT status FROM org_student WHERE node_id = #{nodeId} AND deleted_at = 0 LIMIT 1")
    Integer selectStudentStatus(@Param("nodeId") Long nodeId);

    /** 取单个节点的名称与类型（{@code /auth/me} 的 {@code nodeName} / {@code nodeType}）。 */
    @Select("SELECT id, node_name AS nodeName, node_type AS nodeType "
            + "FROM org_node WHERE id = #{nodeId} AND deleted_at = 0")
    NodeNameRow selectNodeName(@Param("nodeId") Long nodeId);

    /**
     * 批量取节点名，供 {@code nodePath} 面包屑拼接。
     *
     * <p><b>返回行数可能比传入的 id 数少</b>：平台根哨兵（{@code id = 0}）属于
     * {@code tenant_id = 0}，租户插件会把它过滤掉。<b>这是正确行为，不是 bug</b>
     * （{@code NodePath} 类注释逐字如此）—— 而且调用方传进来的 id 已由
     * {@code NodePath.parseAncestorIds} 跳过哨兵，正常路径下不会遇到。
     */
    @Select({"<script>",
            "SELECT id, node_name AS nodeName, node_type AS nodeType FROM org_node",
            " WHERE deleted_at = 0 AND id IN",
            " <foreach item='id' collection='nodeIds' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    List<NodeNameRow> selectNodeNames(@Param("nodeIds") List<Long> nodeIds);

    /** {@code sys_tenant} 的窄投影。 */
    class TenantRow {
        private Long id;
        private String name;
        private Long rootNodeId;
        private LocalDateTime expireTime;
        private Integer status;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getRootNodeId() {
            return rootNodeId;
        }

        public void setRootNodeId(Long rootNodeId) {
            this.rootNodeId = rootNodeId;
        }

        public LocalDateTime getExpireTime() {
            return expireTime;
        }

        public void setExpireTime(LocalDateTime expireTime) {
            this.expireTime = expireTime;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        /** {@code status = 1} 停用，或 {@code expire_time} 早于当前时刻 → {@code 10007}。 */
        public boolean isDisabledOrExpired(LocalDateTime now) {
            if (status != null && status == 1) {
                return true;
            }
            // expire_time 为 NULL 表示永久有效（DDL 注释逐字如此）
            return expireTime != null && expireTime.isBefore(now);
        }
    }

    /** {@code org_node} 的窄投影（只要名称与类型）。 */
    class NodeNameRow {
        private Long id;
        private String nodeName;
        private Integer nodeType;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getNodeName() {
            return nodeName;
        }

        public void setNodeName(String nodeName) {
            this.nodeName = nodeName;
        }

        public Integer getNodeType() {
            return nodeType;
        }

        public void setNodeType(Integer nodeType) {
            this.nodeType = nodeType;
        }
    }
}
