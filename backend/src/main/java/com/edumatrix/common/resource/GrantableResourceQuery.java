package com.edumatrix.common.resource;

import java.util.Collections;
import java.util.List;

import com.edumatrix.common.response.PageResult;

/**
 * 「我可授权的资源列表」（03-02 §9.1 接口 37）的查询条件 —— 跨三类资源统一。
 *
 * <h2>⚠ {@link #getRegrantableIds()} 是<b>已经判完的结果</b>，不是原始授权集</h2>
 * <p>实现方（课程 / 题目 / 视频三个领域）<b>不做任何权限判定</b>，只把这份 ID 清单
 * 拼进 {@code IN (...)}。判定发生在调用方（模块 11 的 {@code org/grant}），
 * 用的是 {@code ResourceOwnerChecker.canRegrant} ——
 * 契约 §2.5 规则 1（拥有）叠加规则 9（跨管辖降级只读）。
 *
 * <p><b>为什么必须是 canRegrant 而不是 canUse</b>：接口 37 的定位逐字是
 * 「本列表即接口 38 授权动作的<b>合法资源全集</b>——列表之外的任何资源 ID
 * 传给接口 38 一律返回 {@code 10301}」。若这里放进跨管辖行（能用、不能再下发），
 * 就会出现<b>列表里看得见、授出去报 10301</b> —— 界面在骗人，
 * 而这是三种失败里最糟的一种：用户照着界面操作，系统告诉他他错了。
 */
public class GrantableResourceQuery {

    /** 「我」所在节点。自有判定为 {@code owner_node_id = 我}。 */
    private Long myNodeId;

    /**
     * 受授权且<b>可再下发</b>的资源 ID（已由调用方用 {@code canRegrant} 过滤）。
     * <b>可能为空集</b>，实现方必须按空集处理 —— 拼一个 {@code IN ()} 是语法错误。
     */
    private List<Long> regrantableIds = Collections.emptyList();

    /** 来源筛选：{@code 1} 自有、{@code 2} 受授权、{@code null} 全部（§9.1 参数表 {@code source}）。 */
    private Integer source;

    /** 资源名称模糊匹配（课程名 / 题干摘要 / 视频名）。 */
    private String keyword;

    /** 科目筛选，<b>仅 {@code resourceType=1} 课程有效</b>；其余实现忽略。 */
    private String subject;

    /** 题库分类 ID，<b>仅 {@code resourceType=2} 题目有效</b>；其余实现忽略。 */
    private Long categoryId;

    /** 已 normalize 的页码（{@code >= 1}）。 */
    private int pageNum = PageResult.DEFAULT_PAGE_NUM;

    /** 已 normalize 的每页条数（{@code 1..100}）。 */
    private int pageSize = PageResult.DEFAULT_PAGE_SIZE;

    /** 只查自有（{@code source=1}）。 */
    public boolean onlyOwned() {
        return source != null && source == GrantableResourceItem.SOURCE_OWNED;
    }

    /** 只查受授权（{@code source=2}）。 */
    public boolean onlyGranted() {
        return source != null && source == GrantableResourceItem.SOURCE_GRANTED;
    }

    public Long getMyNodeId() {
        return myNodeId;
    }

    public void setMyNodeId(Long myNodeId) {
        this.myNodeId = myNodeId;
    }

    public List<Long> getRegrantableIds() {
        return regrantableIds;
    }

    public void setRegrantableIds(List<Long> regrantableIds) {
        this.regrantableIds = regrantableIds == null ? Collections.emptyList() : regrantableIds;
    }

    public Integer getSource() {
        return source;
    }

    public void setSource(Integer source) {
        this.source = source;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public int getPageNum() {
        return pageNum;
    }

    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
