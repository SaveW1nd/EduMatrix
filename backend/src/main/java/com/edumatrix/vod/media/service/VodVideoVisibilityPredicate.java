package com.edumatrix.vod.media.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.vod.media.entity.VodVideo;

/**
 * 媒资可见集的<b>唯一</b>谓词：{@code owner_node_id = 我} ∪ 一份显式给出的资源 ID 清单。
 *
 * <p>与 {@code course/catalog/service/CourseVisibilityPredicate} 同型，
 * 抽出来的理由也一样：模块 11 的接口 37（我可授权的资源列表，03-02 §9.1）
 * 要的是同一条谓词，只是 ID 清单从 {@code canUse} 口径换成 {@code canRegrant} 口径。
 * 照抄一遍就是两份同源实现，而<b>两份都返回 200</b>。
 *
 * <p><b>不回溯祖先链、无继承</b>（契约 §2.5 规则 4）—— 清单里有什么就是什么，
 * 本类不做任何权限判定。
 */
final class VodVideoVisibilityPredicate {

    /** {@code source} = 1：自有。 */
    static final int ONLY_OWNED = 1;

    /** {@code source} = 2：受授权。 */
    static final int ONLY_GRANTED = 2;

    private VodVideoVisibilityPredicate() {
    }

    /**
     * @param ids    自有之外还能看到的媒资 ID；<b>可能为空</b>
     * @param filter {@code 1} 只要自有、{@code 2} 只要受授权、{@code null} 并集
     *               （03-03 §7.3 媒资列表没有这个筛选项，传 {@code null}）
     * @return 传入的 wrapper；若筛选条件导致<b>结果必然为空</b>则返回 {@code null}，
     *         调用方直接回空页 —— 拼一个 {@code id IN ()} 是语法错误
     */
    static LambdaQueryWrapper<VodVideo> apply(LambdaQueryWrapper<VodVideo> wrapper,
                                              Long myNodeId, List<Long> ids, Integer filter) {
        boolean onlyOwn = filter != null && filter == ONLY_OWNED;
        boolean onlyGranted = filter != null && filter == ONLY_GRANTED;
        if (onlyGranted) {
            if (ids.isEmpty()) {
                return null;
            }
            wrapper.in(VodVideo::getId, ids).ne(VodVideo::getOwnerNodeId, myNodeId);
            return wrapper;
        }
        if (onlyOwn || ids.isEmpty()) {
            wrapper.eq(VodVideo::getOwnerNodeId, myNodeId);
            return wrapper;
        }
        wrapper.and(w -> w.eq(VodVideo::getOwnerNodeId, myNodeId).or().in(VodVideo::getId, ids));
        return wrapper;
    }
}
