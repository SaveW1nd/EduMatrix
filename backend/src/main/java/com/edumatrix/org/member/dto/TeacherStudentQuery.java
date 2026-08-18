package com.edumatrix.org.member.dto;

/**
 * 接口 15 教师名下学员列表的查询参数（03-02 §5.5）。
 *
 * <p>{@code status} <b>默认 {@code 0} 在读</b>（§5.5 参数表逐字：「默认 `0`，传空串查全部」）——
 * 与接口 16 的「不传查全部」<b>相反</b>，两个接口不可共用默认值。
 */
public class TeacherStudentQuery {

    private Integer pageNum;

    private Integer pageSize;

    /** 学籍状态；<b>默认 0 在读</b>。传 {@code -1} 表示查全部（对应分册的「传空串」）。 */
    private Integer status;

    /** 学生姓名，模糊匹配。 */
    private String realName;

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }
}
