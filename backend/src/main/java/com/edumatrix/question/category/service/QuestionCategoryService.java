package com.edumatrix.question.category.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.OrgRootGuard;
import com.edumatrix.question.category.dto.CategoryCreateReq;
import com.edumatrix.question.category.dto.CategoryUpdateReq;
import com.edumatrix.question.category.entity.QbCategory;
import com.edumatrix.question.category.mapper.QbCategoryMapper;
import com.edumatrix.question.category.vo.CategoryCreatedVO;
import com.edumatrix.question.category.vo.CategoryNodeVO;

/**
 * 题库分类树（03-04 §1.1~§1.4，接口 1~4）。
 *
 * <h2>分类树【不做节点级过滤】</h2>
 * <p>03-04 §1.1 权限栏逐字：「仅本租户分类（{@code tenant_id} 隔离；分类树是
 * <b>组织无关的目录结构</b>，租户内共享，不做节点级过滤，也不进
 * {@code org_resource_grant} —— 受管资源是题目本身）」。
 * 所以本类<b>一次都不问「我在哪个节点」</b> —— 那是刻意的，不是漏了。
 *
 * <h2>写权限只有 perms 一道门（F-72 定案）</h2>
 * <p>03-04 §1.2 与 PRD F3-1 规则 8 都写「<b>仅 {@code org_admin}</b>」，
 * 而契约 §10 附表 A 与初始化脚本<b>一致地</b>把三个 {@code question:category:*}
 * 绑给了 {@code teacher} —— 也就是说，只写 {@code @SaCheckPermission}
 * <b>教师会照样通过</b>，这是本项目第七次「以为存在、实际从未生效的保障」。
 *
 * <p>定案是<b>改种子数据</b>（{@code V202608200000__revoke_teacher_question_category_perms.sql}
 * 删掉那三行绑定 + 同步订正契约附表 A），<b>不加角色门</b>：
 * 角色门会是全库唯一一处不同构的判定，多一个「会配错的地方」；
 * 而附表 A 自称与脚本同源，改脚本 + 改附表，同源关系保住。
 *
 * <h2>{@code questionCount} 是直属计数</h2>
 * <p>03-04 §1.1 脚注：不含子孙节点，材料题只计父题（{@code parent_id = 0}）。
 *
 * <h2>⚠ 写操作（接口 2/3/4）另有一道闸：<b>仅机构根</b>（F-114 需方定案）</h2>
 * <p>上一段说的「写权限只有 perms 一道门」<b>已经不成立了</b>，别照着它写新代码。
 * 现在是两道：{@code @SaCheckPermission} 判「这个角色能不能碰分类」，
 * {@link OrgRootGuard} 判「你在不在机构根这一层」。
 *
 * <p><b>需方的理由，原样登记</b>：F-114 那 18 个接口之后，分校管理员对题库<b>已经完全只读</b>
 * （建不了题、改不了题、删不了题、停不了用）。那么他新建一个分类<b>放不进任何题</b>，
 * 改一个分类名<b>改的是别人题目的归类</b> —— 这是一个<b>残缺的权限</b>：
 * 只覆盖了一个完整动作的一半，剩下的只有副作用。<b>这类半个权限，要么补全要么去掉。</b>
 *
 * <p><b>一处事实修正</b>：删除分类<b>本来就有引用保护</b> —— 分类下有未删除的子分类或题目时
 * 返回 {@code 30004}，所以<b>只有空分类删得掉</b>，删不掉别人正在用的分类。
 * 真正没有保护、也确实会影响全机构的是<b>新建</b>与<b>改名</b>。
 *
 * <p><b>判定不在本类</b>：与那 18 个接口共用 {@link OrgRootGuard} 同一份
 * （{@code course} / {@code question} / {@code vod} 三个域互不能 import，检查③）。
 * <b>不要在这里写第二份</b>，漂移的表现是「有的收窄了、有的没有」——不报错。
 */
@Service
public class QuestionCategoryService {

    /** 分类树最大层级 —— 防止「移动到自身子孙」的环检测被无限展开拖死。 */
    private static final int MAX_DEPTH = 32;

    private final QbCategoryMapper categoryMapper;
    private final OrgRootGuard orgRootGuard;

    public QuestionCategoryService(QbCategoryMapper categoryMapper, OrgRootGuard orgRootGuard) {
        this.categoryMapper = categoryMapper;
        this.orgRootGuard = orgRootGuard;
    }

    // =====================================================================
    // 接口 1 §1.1 获取题库分类树
    // =====================================================================

    /**
     * @param keyword 分类名称模糊匹配；<b>命中节点连同其祖先链一起返回</b>（03-04 §1.1）
     */
    public List<CategoryNodeVO> tree(String keyword) {
        List<QbCategory> all = categoryMapper.selectList(new LambdaQueryWrapper<QbCategory>()
                .orderByAsc(QbCategory::getSort).orderByAsc(QbCategory::getId));
        Map<Long, Integer> counts = directQuestionCounts();

        Set<Long> keep = keyword == null || keyword.isBlank() ? null : keepWithAncestors(all, keyword);

        Map<Long, CategoryNodeVO> nodes = new LinkedHashMap<>();
        for (QbCategory row : all) {
            if (keep != null && !keep.contains(row.getId())) {
                continue;
            }
            CategoryNodeVO vo = new CategoryNodeVO();
            vo.setId(row.getId());
            vo.setParentId(row.getParentId());
            vo.setCategoryName(row.getCategoryName());
            vo.setSort(row.getSort());
            vo.setQuestionCount(counts.getOrDefault(row.getId(), 0));
            nodes.put(row.getId(), vo);
        }

        List<CategoryNodeVO> roots = new ArrayList<>();
        for (CategoryNodeVO vo : nodes.values()) {
            CategoryNodeVO parent = nodes.get(vo.getParentId());
            if (parent != null) {
                parent.getChildren().add(vo);
            } else {
                // 父节点不在结果集里（顶级，或筛选后父节点被剔除）→ 作为根返回，
                // 而不是整棵消失。契约 §10 那条「自环行会让节点从树上消失」是同一类故障
                roots.add(vo);
            }
        }
        return roots;
    }

    private Map<Long, Integer> directQuestionCounts() {
        Map<Long, Integer> counts = new HashMap<>();
        for (QbCategoryMapper.CategoryCountRow row : categoryMapper.countDirectQuestions()) {
            counts.put(row.getCategoryId(), row.getQuestionCount());
        }
        return counts;
    }

    /** 命中节点 + 其全部祖先（03-04 §1.1：命中节点连同祖先链一起返回）。 */
    private Set<Long> keepWithAncestors(List<QbCategory> all, String keyword) {
        Map<Long, QbCategory> byId = new HashMap<>();
        all.forEach(row -> byId.put(row.getId(), row));
        Set<Long> keep = new LinkedHashSet<>();
        for (QbCategory row : all) {
            if (row.getCategoryName() == null || !row.getCategoryName().contains(keyword)) {
                continue;
            }
            QbCategory cursor = row;
            for (int depth = 0; cursor != null && depth <= MAX_DEPTH; depth++) {
                if (!keep.add(cursor.getId())) {
                    break;  // 祖先链已收过，不必重复上溯
                }
                cursor = byId.get(cursor.getParentId());
            }
        }
        return keep;
    }

    // =====================================================================
    // 接口 2 §1.2 新增题库分类
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public CategoryCreatedVO create(CategoryCreateReq req) {
        orgRootGuard.assertOrgRoot("题库分类");   // F-114 收窄：分类写操作仅机构根
        Long parentId = req.getParentId();
        if (parentId != QbCategory.ROOT_PARENT) {
            requireExisting(parentId);
        }
        assertNameAvailable(parentId, req.getCategoryName(), null);

        QbCategory row = new QbCategory();
        row.setParentId(parentId);
        row.setCategoryName(req.getCategoryName().trim());
        row.setSort(req.getSort() == null ? 0 : req.getSort());
        row.setRemark(req.getRemark());
        categoryMapper.insert(row);
        return new CategoryCreatedVO(row.getId());
    }

    // =====================================================================
    // 接口 3 §1.3 修改题库分类
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategoryUpdateReq req) {
        orgRootGuard.assertOrgRoot("题库分类");   // F-114 收窄：分类写操作仅机构根
        QbCategory row = requireExisting(id);

        Long targetParent = req.getParentId() == null ? row.getParentId() : req.getParentId();
        if (!targetParent.equals(row.getParentId())) {
            assertMovable(row, targetParent);
        }
        String name = req.getCategoryName() == null ? row.getCategoryName() : req.getCategoryName().trim();
        assertNameAvailable(targetParent, name, id);

        QbCategory update = new QbCategory();
        update.setId(id);
        update.setParentId(targetParent);
        update.setCategoryName(name);
        if (req.getSort() != null) {
            update.setSort(req.getSort());
        }
        if (req.getRemark() != null) {
            update.setRemark(req.getRemark());
        }
        categoryMapper.updateById(update);
    }

    // =====================================================================
    // 接口 4 §1.4 删除题库分类（逻辑删除）
    // =====================================================================

    /**
     * 分类下存在<b>未删除的子分类或题目</b>时不可删除 → {@code 30004}。
     *
     * <p>题目计数<b>含材料题子题</b>：子题也有 {@code category_id}，
     * 只数父题会让「删掉分类后子题挂在一个不存在的分类上」这件事悄悄发生。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        orgRootGuard.assertOrgRoot("题库分类");   // F-114 收窄：分类写操作仅机构根
        requireExisting(id);
        long children = categoryMapper.selectCount(new LambdaQueryWrapper<QbCategory>()
                .eq(QbCategory::getParentId, id));
        if (children > 0 || categoryMapper.countQuestionsIn(id) > 0) {
            throw new BizException(ErrorCode.QUESTION_CATEGORY_NOT_EMPTY);
        }
        categoryMapper.deleteById(id);
    }

    // =====================================================================
    // 私有
    // =====================================================================

    /**
     * 路径上的分类：不存在 / 已删除 / 跨租户 —— 一律 404，不暴露存在性
     * （契约 §2.4 三分法第 1 行）。
     */
    private QbCategory requireExisting(Long id) {
        QbCategory row = id == null ? null : categoryMapper.selectById(id);
        if (row == null) {
            throw BizException.notFound(id);
        }
        return row;
    }

    /** 同级不可重名（03-04 §1.2：重名返回 400）。 */
    private void assertNameAvailable(Long parentId, String name, Long selfId) {
        LambdaQueryWrapper<QbCategory> query = new LambdaQueryWrapper<QbCategory>()
                .eq(QbCategory::getParentId, parentId)
                .eq(QbCategory::getCategoryName, name);
        if (selfId != null) {
            query.ne(QbCategory::getId, selfId);
        }
        if (categoryMapper.selectCount(query) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    ErrorCode.BAD_REQUEST.getMsg() + "：同级下已存在同名分类「" + name + "」");
        }
    }

    /**
     * 不可移动到自身或其子孙节点（03-04 §1.3：否则 400）。
     *
     * <p>做法是从<b>目标父节点</b>向上回溯：若在祖先链上遇到自己，那就是在成环。
     * 与契约 §2.3 组织树的成环校验同型 —— 环一旦形成，按 parent 拼树时整棵子树
     * 会从结果里<b>消失</b>（找不到 {@code parent_id = 0} 的入口），而接口 200。
     */
    private void assertMovable(QbCategory row, Long targetParentId) {
        if (targetParentId == QbCategory.ROOT_PARENT) {
            return;
        }
        if (targetParentId.equals(row.getId())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    ErrorCode.BAD_REQUEST.getMsg() + "：不可把分类移动到自身之下");
        }
        QbCategory cursor = requireExisting(targetParentId);
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            if (cursor.getId().equals(row.getId())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        ErrorCode.BAD_REQUEST.getMsg() + "：不可把分类移动到它自己的子孙节点之下");
            }
            if (cursor.getParentId() == QbCategory.ROOT_PARENT) {
                return;
            }
            QbCategory parent = categoryMapper.selectById(cursor.getParentId());
            if (parent == null) {
                return;  // 悬挂 parent_id：按顶级处理，移动本身不受影响
            }
            cursor = parent;
        }
        throw new BizException(ErrorCode.BAD_REQUEST,
                ErrorCode.BAD_REQUEST.getMsg() + "：分类层级超过 " + MAX_DEPTH + " 层，疑似成环");
    }
}
