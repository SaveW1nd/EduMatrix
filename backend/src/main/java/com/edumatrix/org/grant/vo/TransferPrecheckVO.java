package com.edumatrix.org.grant.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 接口 52 归属变更影响面预检的响应（03-02 §6.12 响应示例逐字段）。
 *
 * <h2>三个处置选项，默认「保持现状」</h2>
 * <p>F-21 定案第 1 条：契约 §2.5 规则 6 把跨管辖定为<b>合法状态</b>；
 * 且本系统的教师是<b>督学导师不是授课老师</b>，督学动作（看进度、完播率、催作业）
 * 全按学生维度查、<b>不需要课程授权</b>。
 * 强制二选一等于逼操作者在「给导师一堆用不上的授权」和「把学员的课停掉」之间挑一个 ——
 * <b>默认必须是第三个选项：什么都不做</b>。
 */
public class TransferPrecheckVO {

    /** {@code 2} 分配导师（接口 20/21）｜{@code 3} 转交管理员（接口 22）。 */
    private Integer action;

    private String actionName;

    /** 本次预检涉及的学生数。 */
    private Integer studentCount;

    private Summary summary;

    private List<OutOfScopeResource> outOfScopeGrants = new ArrayList<>();

    private List<Option> options = new ArrayList<>();

    /** 恒为 {@code true} —— 与接口 39 同义：任何处置都不删学习记录。 */
    private Boolean learningRecordsRetained;

    public Integer getAction() {
        return action;
    }

    public void setAction(Integer action) {
        this.action = action;
    }

    public String getActionName() {
        return actionName;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public Integer getStudentCount() {
        return studentCount;
    }

    public void setStudentCount(Integer studentCount) {
        this.studentCount = studentCount;
    }

    public Summary getSummary() {
        return summary;
    }

    public void setSummary(Summary summary) {
        this.summary = summary;
    }

    public List<OutOfScopeResource> getOutOfScopeGrants() {
        return outOfScopeGrants;
    }

    public void setOutOfScopeGrants(List<OutOfScopeResource> outOfScopeGrants) {
        this.outOfScopeGrants = outOfScopeGrants == null ? new ArrayList<>() : outOfScopeGrants;
    }

    public List<Option> getOptions() {
        return options;
    }

    public void setOptions(List<Option> options) {
        this.options = options == null ? new ArrayList<>() : options;
    }

    public Boolean getLearningRecordsRetained() {
        return learningRecordsRetained;
    }

    public void setLearningRecordsRetained(Boolean learningRecordsRetained) {
        this.learningRecordsRetained = learningRecordsRetained;
    }

    /** 汇总。 */
    public static class Summary {
        private Integer resourceCount;
        private Integer grantableByMeCount;
        private Integer notGrantableCount;

        /**
         * 至少涉及一项跨管辖授权的<b>去重</b>学员数。
         *
         * <p><b>不等于各资源 {@code affectedStudentCount} 之和</b>（§6.12 字段说明逐字）——
         * 同一学员可能涉及多门课。相加会把他数很多次，而那个数字会显示在确认弹窗上。
         */
        private Integer affectedStudentCount;

        public Summary() {
        }

        public Summary(Integer resourceCount, Integer grantableByMeCount,
                       Integer notGrantableCount, Integer affectedStudentCount) {
            this.resourceCount = resourceCount;
            this.grantableByMeCount = grantableByMeCount;
            this.notGrantableCount = notGrantableCount;
            this.affectedStudentCount = affectedStudentCount;
        }

        public Integer getResourceCount() {
            return resourceCount;
        }

        public void setResourceCount(Integer resourceCount) {
            this.resourceCount = resourceCount;
        }

        public Integer getGrantableByMeCount() {
            return grantableByMeCount;
        }

        public void setGrantableByMeCount(Integer grantableByMeCount) {
            this.grantableByMeCount = grantableByMeCount;
        }

        public Integer getNotGrantableCount() {
            return notGrantableCount;
        }

        public void setNotGrantableCount(Integer notGrantableCount) {
            this.notGrantableCount = notGrantableCount;
        }

        public Integer getAffectedStudentCount() {
            return affectedStudentCount;
        }

        public void setAffectedStudentCount(Integer affectedStudentCount) {
            this.affectedStudentCount = affectedStudentCount;
        }
    }

    /** 按<b>资源</b>归并的一行（不按人归并 —— 500 人逐人弹窗不可用，§6.12 说明段）。 */
    public static class OutOfScopeResource {
        private Integer resourceType;
        private Long resourceId;
        private String resourceName;
        private Integer affectedStudentCount;

        /**
         * 当前操作者<b>是否拥有</b>该资源。
         *
         * <p><b>无权时不返回 {@code 10301}</b>（§6.12 说明段逐字）：那是<b>执行接口 38 时</b>
         * 的拒绝码；在只读预检里抛它会让整个预检失败，
         * 而操作者<b>恰恰需要看到</b>「这门课我授不了，得找共同上级」——
         * 接口 22 的完整流程本就是三步，第二步必须由共同上级执行。
         */
        private Boolean grantableByMe;

        private List<SampleStudent> sampleStudents = new ArrayList<>();
        private Boolean sampleTruncated;

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

        public String getResourceName() {
            return resourceName;
        }

        public void setResourceName(String resourceName) {
            this.resourceName = resourceName;
        }

        public Integer getAffectedStudentCount() {
            return affectedStudentCount;
        }

        public void setAffectedStudentCount(Integer affectedStudentCount) {
            this.affectedStudentCount = affectedStudentCount;
        }

        public Boolean getGrantableByMe() {
            return grantableByMe;
        }

        public void setGrantableByMe(Boolean grantableByMe) {
            this.grantableByMe = grantableByMe;
        }

        public List<SampleStudent> getSampleStudents() {
            return sampleStudents;
        }

        public void setSampleStudents(List<SampleStudent> sampleStudents) {
            this.sampleStudents = sampleStudents == null ? new ArrayList<>() : sampleStudents;
        }

        public Boolean getSampleTruncated() {
            return sampleTruncated;
        }

        public void setSampleTruncated(Boolean sampleTruncated) {
            this.sampleTruncated = sampleTruncated;
        }
    }

    /** 学员样本，<b>最多前 50 个</b>（§6.12 字段说明）。 */
    public record SampleStudent(Long nodeId, String realName) {
    }

    /** 处置选项；{@code keep} <b>恒排第一且 {@code isDefault = true}</b>。 */
    public record Option(String value, String label, Boolean isDefault, String description) {
    }
}
