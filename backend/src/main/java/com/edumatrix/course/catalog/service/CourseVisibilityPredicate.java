package com.edumatrix.course.catalog.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.course.catalog.entity.CrsCourse;

/**
 * 课程可见集的<b>唯一</b>谓词：{@code owner_node_id = 我} ∪ 一份显式给出的资源 ID 清单。
 *
 * <h2>为什么把它从 {@code CourseService} 里抽出来</h2>
 * <p>模块 11 的接口 37（我可授权的资源列表，03-02 §9.1）要的是同一条谓词，
 * 只是那份 ID 清单换成了「受授权<b>且可再下发</b>」的（{@code canRegrant} 过滤后）。
 * 照抄一遍就是两份同源实现 —— 本项目的 4 号失败模式，且两份都返回 200。
 *
 * <h2>清单的含义由<b>调用方</b>决定，本类不判定任何权限</h2>
 * <table border="1">
 *   <caption>同一条谓词的两个调用点</caption>
 *   <tr><th>调用点</th><th>传进来的 ID 清单</th><th>依据</th></tr>
 *   <tr><td>{@code CourseService#page}（03-03 §1.1 课程列表）</td>
 *       <td>{@code canUse} 口径：被有效授权的全部课程</td>
 *       <td>契约 §2.5 规则 4（能不能<b>用</b>）</td></tr>
 *   <tr><td>{@code CourseGrantableProvider#page}（03-02 §9.1 可授权清单）</td>
 *       <td>{@code canRegrant} 口径：再去掉跨管辖那些</td>
 *       <td>契约 §2.5 规则 1 + 规则 9（能不能<b>再下发</b>）</td></tr>
 * </table>
 * <p><b>两个口径不同是对的，谓词相同也是对的</b> —— 差别全部落在入参上，
 * 而不是落在两段各写一遍的 SQL 上。
 */
final class CourseVisibilityPredicate {

    /** {@code source} / {@code grantType} = 1：自有。 */
    static final int ONLY_OWNED = 1;

    /** {@code source} / {@code grantType} = 2：受授权。 */
    static final int ONLY_GRANTED = 2;

    private CourseVisibilityPredicate() {
    }

    /**
     * 把可见性判定翻成 wrapper 条件。
     *
     * @param ids    自有之外还能看到的课程 ID；<b>可能为空</b>
     * @param filter {@code 1} 只要自有、{@code 2} 只要受授权、{@code null} 并集
     * @return 传入的 wrapper；若筛选条件导致<b>结果必然为空</b>（只要受授权而一条都没有）
     *         则返回 {@code null}，调用方直接回空页 —— 拼一个 {@code id IN ()} 是语法错误
     */
    static LambdaQueryWrapper<CrsCourse> apply(LambdaQueryWrapper<CrsCourse> wrapper,
                                               Long myNodeId, List<Long> ids, Integer filter) {
        boolean onlyOwn = filter != null && filter == ONLY_OWNED;
        boolean onlyGranted = filter != null && filter == ONLY_GRANTED;
        if (onlyGranted) {
            if (ids.isEmpty()) {
                return null;
            }
            wrapper.in(CrsCourse::getId, ids).ne(CrsCourse::getOwnerNodeId, myNodeId);
            return wrapper;
        }
        if (onlyOwn || ids.isEmpty()) {
            wrapper.eq(CrsCourse::getOwnerNodeId, myNodeId);
            return wrapper;
        }
        wrapper.and(w -> w.eq(CrsCourse::getOwnerNodeId, myNodeId).or().in(CrsCourse::getId, ids));
        return wrapper;
    }
}
