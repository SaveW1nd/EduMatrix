package com.edumatrix.common.response;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 分页响应体（00-通用约定 §4.2）。
 *
 * <p>分页接口的 {@code data} <b>固定</b>为 {@code {total, list}}，允许附加<b>可选</b>的
 * {@code summary} 承载与筛选条件相关的聚合信息（如批改进度，03-04 §5.1）。
 * <b>除这三者外不得有第四个同级字段</b> —— 多一个字段，两个前端工程的拆包逻辑就要各改一次。
 *
 * <p>{@code list} 无数据时是 {@code []}（{@code total} 为 0），<b>不返回 null</b>。
 *
 * <p>{@code summary} 用 {@link JsonInclude.Include#NON_NULL} 修饰：不带聚合信息的接口
 * 响应里根本不出现这个键，而不是出现一个 {@code "summary": null} —— 后者会让前端误以为
 * 这个接口本该有聚合信息、只是这次算不出来。
 *
 * @param <T> 列表元素类型
 */
public class PageResult<T> {

    /** 分页每页条数上限（00-通用约定 §4.1：最大 100，超出按 100 处理）。 */
    public static final int MAX_PAGE_SIZE = 100;
    /** 分页每页条数默认值。 */
    public static final int DEFAULT_PAGE_SIZE = 10;
    /** 分页页码默认值（从 1 开始）。 */
    public static final int DEFAULT_PAGE_NUM = 1;

    private long total;

    private List<T> list;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object summary;

    public PageResult() {
        this.total = 0L;
        this.list = new ArrayList<>();
    }

    private PageResult(long total, List<T> list, Object summary) {
        this.total = total;
        this.list = list == null ? new ArrayList<>() : list;
        this.summary = summary;
    }

    public static <T> PageResult<T> of(long total, List<T> list) {
        return new PageResult<>(total, list, null);
    }

    public static <T> PageResult<T> of(long total, List<T> list, Object summary) {
        return new PageResult<>(total, list, summary);
    }

    /** 空结果：{@code total = 0}、{@code list = []}。 */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(0L, new ArrayList<>(), null);
    }

    /**
     * 把客户端传来的 {@code pageSize} 收敛到合法区间。
     *
     * <p><b>超出上限按 100 处理，而不是报 400</b> —— 00-通用约定 §4.1 的原话是「最大 100，
     * 超出按 100 处理」。这里是第一道；第二道在 {@code MybatisPlusConfig} 的分页插件
     * （{@code maxLimit = 100}），即使某个接口忘了走本方法，SQL 层也拦得住。
     * 两道都要有：漏掉第二道时，一次 {@code pageSize=100000} 的请求就是一次拖库。
     */
    public static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 把客户端传来的 {@code pageNum} 收敛到合法区间（从 1 开始）。 */
    public static int normalizePageNum(Integer pageNum) {
        if (pageNum == null || pageNum <= 0) {
            return DEFAULT_PAGE_NUM;
        }
        return pageNum;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list == null ? new ArrayList<>() : list;
    }

    public Object getSummary() {
        return summary;
    }

    public void setSummary(Object summary) {
        this.summary = summary;
    }
}
