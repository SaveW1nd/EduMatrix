package com.edumatrix.question.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.resource.GrantableResourceItem;
import com.edumatrix.common.resource.GrantableResourceQuery;
import com.edumatrix.common.resource.GrantableResourceReader;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.question.support.QuestionFixtures;
import com.edumatrix.question.support.QuestionIntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块 11 · C1：{@code QuestionGrantableProvider}（{@code resource_type = 2}）。
 *
 * <p>课程与视频那两个在 {@code common/resource/GrantableResourceProviderIT}
 * （夹具在另一个租户前缀下，两边不能合成一个类）。
 *
 * <h2>本类特有的那一条：材料题子题不得出现在可授权清单里</h2>
 * <p>子题状态随父题联动、不能脱离父题被删除（03-04 §2.7 / §2.8），
 * 「把一道子题单独授给某个节点」本身就说不通。这条闭合<b>靠的是复用
 * {@code QuestionPageMapper} 里那个 {@code q.parent_id = 0}</b> ——
 * 删掉它不会报错，只会让子题悄悄可授，然后在授权表里长出一批永远不被任何鉴权路径读到的行。
 */
class QuestionGrantableProviderIT extends QuestionIntegrationTestBase {

    @Autowired
    private GrantableResourceReader grantableReader;

    @Test
    @DisplayName("题目：自有 ∪ 传进来的清单；source=1/2 各自切一半")
    void questionPageSplitsOwnedAndGranted() throws Exception {
        questionFixtures.grantQuestion(QuestionFixtures.Q_SINGLE, QuestionFixtures.TA,
                QuestionFixtures.TENANT_ID);

        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.TA, () -> {
            PageResult<GrantableResourceItem> all = page(QuestionFixtures.TA,
                    List.of(QuestionFixtures.Q_SINGLE), null);
            assertThat(ids(all)).contains(QuestionFixtures.Q_TA, QuestionFixtures.Q_SINGLE);

            assertThat(ids(page(QuestionFixtures.TA, List.of(QuestionFixtures.Q_SINGLE),
                    GrantableResourceItem.SOURCE_OWNED)))
                    .contains(QuestionFixtures.Q_TA)
                    .doesNotContain(QuestionFixtures.Q_SINGLE);
            assertThat(ids(page(QuestionFixtures.TA, List.of(QuestionFixtures.Q_SINGLE),
                    GrantableResourceItem.SOURCE_GRANTED)))
                    .containsExactly(QuestionFixtures.Q_SINGLE);
        });
    }

    @Test
    @DisplayName("题目：材料题【父题在、子题不在】可授权清单里")
    void materialChildrenAreNotGrantable() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            List<Long> listed = ids(page(QuestionFixtures.ROOT, List.of(), null));
            assertThat(listed)
                    .as("父题必须在 —— 授权材料题就是授权父题")
                    .contains(QuestionFixtures.Q_MATERIAL);
            assertThat(listed)
                    .as("子题不得单独出现：状态随父题联动、不能脱离父题删除（03-04 §2.7/§2.8），"
                            + "「单独授一道子题」说不通。这条靠 QuestionPageMapper 的 parent_id = 0 闭合")
                    .doesNotContain(QuestionFixtures.Q_CHILD_1, QuestionFixtures.Q_CHILD_2);
        });
    }

    @Test
    @DisplayName("题目：source=2 且清单为空 → 空页，不退化成不加过滤")
    void emptyRegrantableListMustNotFallBackToNoFilter() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.TA, () -> assertThat(
                page(QuestionFixtures.TA, List.of(), GrantableResourceItem.SOURCE_GRANTED).getTotal())
                .as("一条可再下发的授权都没有 → 0 行；若列出了 ROOT 的题，说明空集分支写错了")
                .isZero());
    }

    @Test
    @DisplayName("题目：categoryId 含全部子孙分类 —— 与 03-04 §2.1 题库列表同一条口径")
    void categoryFilterIncludesDescendants() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            GrantableResourceQuery query = query(QuestionFixtures.ROOT, List.of(), null);
            query.setCategoryId(QuestionFixtures.CAT_MATH);
            List<Long> underMath = ids(grantableReader.page(ResourceType.QUESTION, query));

            query.setCategoryId(QuestionFixtures.CAT_ALGEBRA);
            List<Long> underAlgebra = ids(grantableReader.page(ResourceType.QUESTION, query));

            assertThat(underMath)
                    .as("「数学」应含其子分类「代数」下的题 —— 同一个分类树在题库页与下发页"
                            + "出不同结果，而两边都返回 200")
                    .containsAll(underAlgebra);

            query.setCategoryId(QuestionFixtures.CAT_EMPTY);
            assertThat(grantableReader.page(ResourceType.QUESTION, query).getTotal())
                    .as("空分类必须真的是 0，否则说明 categoryId 根本没被拼进 SQL")
                    .isZero();
        });
    }

    @Test
    @DisplayName("题目：extra 带齐 §9.1 的四个字段；namesOf 回题干摘要且跳过跨租户")
    void extraAndNamesOf() throws Exception {
        runAsTestUser(QuestionFixtures.TENANT_ID, QuestionFixtures.ROOT, () -> {
            PageResult<GrantableResourceItem> page = page(QuestionFixtures.ROOT, List.of(), null);
            assertThat(page.getList().get(0).getExtra())
                    .containsOnlyKeys("questionType", "difficulty", "categoryName", "currentVersion");

            Map<Long, String> names = grantableReader.namesOf(ResourceType.QUESTION,
                    List.of(QuestionFixtures.Q_SINGLE, QuestionFixtures.Q_OTHER, 9L));
            assertThat(names).containsOnlyKeys(QuestionFixtures.Q_SINGLE);
            assertThat(names.get(QuestionFixtures.Q_SINGLE)).isNotBlank();
        });
    }

    private PageResult<GrantableResourceItem> page(long myNodeId, List<Long> regrantableIds,
                                                   Integer source) {
        return grantableReader.page(ResourceType.QUESTION, query(myNodeId, regrantableIds, source));
    }

    private GrantableResourceQuery query(long myNodeId, List<Long> regrantableIds, Integer source) {
        GrantableResourceQuery query = new GrantableResourceQuery();
        query.setMyNodeId(myNodeId);
        query.setRegrantableIds(new ArrayList<>(regrantableIds));
        query.setSource(source);
        query.setPageNum(1);
        query.setPageSize(100);
        return query;
    }

    private static List<Long> ids(PageResult<GrantableResourceItem> page) {
        return page.getList().stream().map(GrantableResourceItem::getResourceId).toList();
    }
}
