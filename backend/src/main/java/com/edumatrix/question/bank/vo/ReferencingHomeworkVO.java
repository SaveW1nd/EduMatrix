package com.edumatrix.question.bank.vo;

import java.time.LocalDateTime;

/**
 * 30001 失败响应里的 {@code referencedHomeworks}（03-04 §2.7 失败响应示例）。
 *
 * <p>不只返回一个错误码，是因为「题目已被作业引用，不可停用」之后
 * 用户要做的下一件事是<b>去找那些作业</b>；只给码等于让他自己翻。
 */
public class ReferencingHomeworkVO {

    private Long homeworkId;
    private String homeworkName;
    private Integer homeworkStatus;
    private LocalDateTime deadline;

    public Long getHomeworkId() {
        return homeworkId;
    }

    public void setHomeworkId(Long homeworkId) {
        this.homeworkId = homeworkId;
    }

    public String getHomeworkName() {
        return homeworkName;
    }

    public void setHomeworkName(String homeworkName) {
        this.homeworkName = homeworkName;
    }

    public Integer getHomeworkStatus() {
        return homeworkStatus;
    }

    public void setHomeworkStatus(Integer homeworkStatus) {
        this.homeworkStatus = homeworkStatus;
    }

    public LocalDateTime getDeadline() {
        return deadline;
    }

    public void setDeadline(LocalDateTime deadline) {
        this.deadline = deadline;
    }
}
