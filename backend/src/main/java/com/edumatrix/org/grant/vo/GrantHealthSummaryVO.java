package com.edumatrix.org.grant.vo;

/**
 * 接口 51 的 {@code summary} —— <b>两个数，永远分开</b>（契约 §2.5 规则 6）。
 *
 * <p>本类<b>刻意不提供</b> {@code getTotal()}：提供了就一定有人用，
 * 而一旦相加，任何一次教师调岗或学员转交都会让「一致性指标」永久非 0，
 * 持续假警报，最终结果是运维关掉告警、真悬挂也没人看。
 * 本项目在 <b>F-20 已经为这条踩过一次</b>。
 */
public class GrantHealthSummaryVO {

    /** 真悬挂条数，<b>指标目标值恒为 0</b>（契约 §7.1 {@code grant_dangling_count}）。 */
    private Integer danglingCount;

    /** 跨管辖条数，<b>不计入一致性指标</b>，仅作待办提示。 */
    private Integer crossScopeCount;

    public GrantHealthSummaryVO() {
    }

    public GrantHealthSummaryVO(Integer danglingCount, Integer crossScopeCount) {
        this.danglingCount = danglingCount;
        this.crossScopeCount = crossScopeCount;
    }

    public Integer getDanglingCount() {
        return danglingCount;
    }

    public void setDanglingCount(Integer danglingCount) {
        this.danglingCount = danglingCount;
    }

    public Integer getCrossScopeCount() {
        return crossScopeCount;
    }

    public void setCrossScopeCount(Integer crossScopeCount) {
        this.crossScopeCount = crossScopeCount;
    }
}
