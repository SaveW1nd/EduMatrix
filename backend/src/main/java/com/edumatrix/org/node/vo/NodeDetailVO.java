package com.edumatrix.org.node.vo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 节点详情（03-02 §3.2）：节点自身 + 面包屑 + 直接子节点的类型分布 + 已获授权资源数。
 * 供节点编辑页与授权目标选择器使用。
 */
public class NodeDetailVO {

    private Long id;
    private Long parentId;
    private String parentName;
    private String ancestors;
    private String nodeName;
    private Integer nodeType;
    private Long refUserId;
    private String refUserName;
    private String refUserPhone;
    private Integer sort;
    private Integer status;
    private Integer childCount;
    private Integer studentCount;

    /**
     * 从<b>租户根节点</b>到本节点的面包屑，按层级正序。
     *
     * <p><b>不含平台根哨兵</b>（{@code id = 0}）：契约 §2.9 定案不放行它，
     * 且面包屑的口径就是「自租户根到自身」——平台根出现在租户面包屑里反而是越界
     * （{@code common/subtree/NodePath} 的类注释逐字记过）。
     */
    private List<PathItem> path = new ArrayList<>();

    /** <b>直接子节点</b>按类型的数量分布（不含孙节点）。 */
    private ChildStat childStat = new ChildStat();

    /**
     * 本节点<b>已获授权</b>且在有效期内的资源数（{@code org_resource_grant} 口径，
     * <b>不含</b>其祖先持有但未下发给本节点的资源 —— 契约 §2.5 规则 3「不向下继承」）。
     */
    private GrantedResourceStat grantedResourceStat = new GrantedResourceStat();

    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 面包屑的一项。 */
    public static class PathItem {
        private Long id;
        private String nodeName;
        private Integer nodeType;

        public PathItem() {
        }

        public PathItem(Long id, String nodeName, Integer nodeType) {
            this.id = id;
            this.nodeName = nodeName;
            this.nodeType = nodeType;
        }

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

    /**
     * 直接子节点的类型分布。
     *
     * <p><b>{@code orgCount} 恒为 0</b>：契约 §2.3「不设独立于人的组织单元节点」，
     * 组织层级由管理员节点的嵌套表达。字段保留是因为 §3.2 的响应示例里有它，
     * 前端按它排版；<b>不要因为它恒为 0 就删掉</b>，那会让响应形状与分册对不上。
     */
    public static class ChildStat {
        private int orgCount;
        private int adminCount;
        private int teacherCount;
        private int studentCount;

        public int getOrgCount() {
            return orgCount;
        }

        public void setOrgCount(int orgCount) {
            this.orgCount = orgCount;
        }

        public int getAdminCount() {
            return adminCount;
        }

        public void setAdminCount(int adminCount) {
            this.adminCount = adminCount;
        }

        public int getTeacherCount() {
            return teacherCount;
        }

        public void setTeacherCount(int teacherCount) {
            this.teacherCount = teacherCount;
        }

        public int getStudentCount() {
            return studentCount;
        }

        public void setStudentCount(int studentCount) {
            this.studentCount = studentCount;
        }
    }

    /** 已获授权资源数，按 {@code resource_type} 分（1 课程 2 题目 3 视频）。 */
    public static class GrantedResourceStat {
        private int courseCount;
        private int questionCount;
        private int videoCount;

        public int getCourseCount() {
            return courseCount;
        }

        public void setCourseCount(int courseCount) {
            this.courseCount = courseCount;
        }

        public int getQuestionCount() {
            return questionCount;
        }

        public void setQuestionCount(int questionCount) {
            this.questionCount = questionCount;
        }

        public int getVideoCount() {
            return videoCount;
        }

        public void setVideoCount(int videoCount) {
            this.videoCount = videoCount;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getParentName() {
        return parentName;
    }

    public void setParentName(String parentName) {
        this.parentName = parentName;
    }

    public String getAncestors() {
        return ancestors;
    }

    public void setAncestors(String ancestors) {
        this.ancestors = ancestors;
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

    public Long getRefUserId() {
        return refUserId;
    }

    public void setRefUserId(Long refUserId) {
        this.refUserId = refUserId;
    }

    public String getRefUserName() {
        return refUserName;
    }

    public void setRefUserName(String refUserName) {
        this.refUserName = refUserName;
    }

    public String getRefUserPhone() {
        return refUserPhone;
    }

    public void setRefUserPhone(String refUserPhone) {
        this.refUserPhone = refUserPhone;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getChildCount() {
        return childCount;
    }

    public void setChildCount(Integer childCount) {
        this.childCount = childCount;
    }

    public Integer getStudentCount() {
        return studentCount;
    }

    public void setStudentCount(Integer studentCount) {
        this.studentCount = studentCount;
    }

    public List<PathItem> getPath() {
        return path;
    }

    public void setPath(List<PathItem> path) {
        this.path = path == null ? new ArrayList<>() : path;
    }

    public ChildStat getChildStat() {
        return childStat;
    }

    public void setChildStat(ChildStat childStat) {
        this.childStat = childStat;
    }

    public GrantedResourceStat getGrantedResourceStat() {
        return grantedResourceStat;
    }

    public void setGrantedResourceStat(GrantedResourceStat grantedResourceStat) {
        this.grantedResourceStat = grantedResourceStat;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
