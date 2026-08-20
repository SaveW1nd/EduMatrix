package com.edumatrix.question.bank.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.question.category.entity.QbCategory;
import com.edumatrix.question.category.mapper.QbCategoryMapper;

/**
 * 「按分类筛题目」的<b>唯一</b>展开口径：一个分类 ID → 它自己 + 全部子孙分类。
 *
 * <h2>为什么抽出来</h2>
 * <p>两个接口要用同一条口径：
 * <ul>
 *   <li>03-04 §2.1 分页查询题目 —— 参数表逐字「含其全部子孙分类」；
 *   <li>03-02 §9.1 我可授权的资源列表 —— 参数表只写「题库分类 ID」，<b>没说含不含子孙</b>。
 * </ul>
 * <p><b>取「含子孙」，理由是它是全库唯一被定义过的语义</b>：同一个分类树、同一个筛选框，
 * 在题库页选「数学」出 500 道、到下发页选「数学」只出 12 道（只有挂在「数学」本级的），
 * 而两个页面都不报错 —— 使用者只会认为下发页漏了资源。
 * 这条属实现方自定，已登记（需方可推翻）。
 *
 * <p>抽成一个构件而不是把 {@code QuestionQueryService} 的私有方法改成包级可见：
 * 后者会让下发侧为了一个 20 行的辅助方法注入一个有 9 个依赖的大 Service。
 */
@Component
public class QuestionCategoryScope {

    private final QbCategoryMapper categoryMapper;

    public QuestionCategoryScope(QbCategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    /**
     * 分类筛选<b>含其全部子孙分类</b>。
     *
     * @return {@code null} 表示不按分类筛（<b>不是空集</b> —— 空集会把结果筛成 0 行）
     */
    public List<Long> withDescendants(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        // 租户条件由插件注入；deleted_at = 0 由 @TableLogic 自动追加
        List<QbCategory> all = categoryMapper.selectList(new LambdaQueryWrapper<>());
        Map<Long, List<Long>> childrenOf = new LinkedHashMap<>();
        all.forEach(row -> childrenOf.computeIfAbsent(row.getParentId(), k -> new ArrayList<>())
                .add(row.getId()));

        Set<Long> collected = new LinkedHashSet<>();
        List<Long> frontier = new ArrayList<>(List.of(categoryId));
        while (!frontier.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (Long id : frontier) {
                if (!collected.add(id)) {
                    continue;   // 分类树成环时的兜底：收过就不再展开
                }
                next.addAll(childrenOf.getOrDefault(id, List.of()));
            }
            frontier = next;
        }
        return List.copyOf(collected);
    }
}
