package com.edumatrix.common.question;

import java.util.List;

/**
 * 题目可见性判定 —— 实现 03-04 §0.1 的判定公式，模块 11 / 15 共用。
 *
 * <p>接口在 {@code common/}、实现在 {@code question/bank/}，消费方按接口注入
 * （与 {@code common/course/LessonVisibilityChecker} ↔
 * {@code course/catalog/LessonVisibilityProvider} 同型）。
 * <b>理由是约定检查 ③</b>：八个领域包不得互相 import，而 {@code org}（模块 11）与
 * {@code homework}（模块 15）都要问这个问题，只能经 {@code common/} 交汇。
 *
 * <h2>判定公式（03-04 §0.1，逐字）</h2>
 * <blockquote>可见 = 我的节点就是该题目的 {@code owner_node_id}，
 * 或该题目已被显式授权给我的节点（{@code org_resource_grant} 命中且当前时间在有效期内）。</blockquote>
 * <ul>
 *   <li><b>不回溯祖先链、无继承</b>：只看 {@code target_node_id = 我的节点} 这一条命中。
 *       上级拥有 ≠ 我自动拥有；<b>父级授权给了我的下级也不等于授权给了我</b>；
 *   <li><b>不向下展开</b>：我拥有某题不代表我的下级可用；
 *   <li><b>材料题以父题为授权粒度</b>：授权父题即连带其全部子题。
 *       因此子题的可见性一律<b>折算到父题</b> —— 这一条是本接口最容易漏的地方，
 *       漏了的表现是「被授权方能看见父题、拿子题 ID 查详情却 404」，而接口本身不报错。
 * </ul>
 *
 * <p><b>模块 11 完成后这里才有第二个分支</b>（当前 {@code org_resource_grant} 里
 * {@code resource_type=2} 的行没有任何接口写得进去）。但实现从第一天起就走
 * {@code common/grant/ResourceGrantReader}，<b>不写「模块 11 还没做」的分支</b> ——
 * 那种分支上线后没人记得删。
 */
public interface QuestionVisibilityChecker {

    /**
     * 该节点当前可见的<b>父题与普通题</b> ID 全集 = 自有 ∪ 显式授权且在有效期内。
     *
     * <p><b>子题不在返回值里</b>：授权粒度是父题，子题随父题判定。
     *
     * <p><b>本模块的分页查询不调它</b>，而是用
     * {@code owner_node_id = :my OR id IN (:grantedIds)} —— 那会把上千个自有题 ID
     * 塞进 {@code IN}。两处用的是<b>同两个输入</b>（我的节点 + 同一个
     * {@code ResourceGrantReader} 给出的授权 ID 集），是一条谓词的两种写法，
     * 不是两份实现。本方法供跨模块（15 选题器校验）批量判定用。
     */
    List<Long> visibleIds(Long myNodeId);

    /**
     * 断言题目对<b>当前会话节点</b>可见；不通过一律抛 <b>404</b>。
     *
     * <p>「不存在 / 已逻辑删除 / 跨租户 / 存在但不可见」<b>四种情形同一个结果</b>
     * —— 契约 §2.4 三分法第 1 行「访问路径上的资源而该资源不在我的可见范围内 → 404，
     * 不暴露存在性」，以及模块 08 的 F-42 定案（两侧给出同一个响应，否则可被拿来探测存在性）。
     *
     * <p>子题的可见性折算到其父题。
     */
    VisibleQuestion assertVisible(Long questionId);

    /**
     * 可见题目的窄视图。
     *
     * @param id             题目物理 ID
     * @param parentId       材料题子题指向父题；普通题与父题为 {@code 0}
     * @param ownerNodeId    归属节点
     * @param questionType   题型（契约 §5）
     * @param currentVersion 当前版本号
     * @param status         0 草稿 1 启用 2 停用
     * @param grantType      1 自有（{@code owner_node_id} = 我的节点）2 被授权（只读可用）
     */
    record VisibleQuestion(Long id, Long parentId, Long ownerNodeId, Integer questionType,
                           Integer currentVersion, Integer status, int grantType) {

        /** 1 自有。 */
        public static final int GRANT_TYPE_OWNED = 1;
        /** 2 被授权 —— <b>只读可用</b>：可选进作业、可发布，但改/停用/删一律 403。 */
        public static final int GRANT_TYPE_GRANTED = 2;

        public boolean owned() {
            return grantType == GRANT_TYPE_OWNED;
        }

        /** 材料题子题（{@code parent_id != 0}）。 */
        public boolean isChild() {
            return parentId != null && parentId != 0L;
        }
    }
}
