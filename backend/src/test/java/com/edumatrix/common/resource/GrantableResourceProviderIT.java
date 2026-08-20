package com.edumatrix.common.resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.edumatrix.common.response.PageResult;
import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模块 11 · C1：{@link GrantableResourceProvider} 三个实现的行为自检。
 *
 * <p>本类同时覆盖 <b>课程（1）与视频（3）</b> —— {@code CourseFixtures} 两类都播种。
 * 题目（2）在 {@code question/bank/QuestionGrantableProviderIT}，因为它的夹具在另一个租户前缀下。
 *
 * <h2>这些用例在守什么</h2>
 * <ol>
 *   <li><b>「只要受授权、而一条都没有」必须返回空页，不能退化成不加过滤。</b>
 *       拼一个 {@code IN ()} 是语法错误，所以实现里有一条「空集直接回空页」的分支 ——
 *       而写错成「空集就不加这个条件」不会报错，只会<b>把全机构的资源都列出来</b>；
 *   <li><b>批量归属查询与逐个查必须逐字相同。</b> {@code ownerNodeIdsOf} 是为了
 *       500 个资源不发 500 次往返而加的覆写，键写反（用 owner 当键）不会报错，
 *       只会让授权引擎判错 owner —— 接口 200、字段齐全、结果错；
 *   <li><b>{@code namesOf} 对查不到的键必须「不出现」而不是 {@code null} 值。</b>
 *       跨租户 / 已删除的资源名若以 {@code null} 混进 Map，调用方分不清
 *       「这个资源没名字」与「这个资源我根本看不到」。
 * </ol>
 */
class GrantableResourceProviderIT extends CourseIntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GrantableResourceReader grantableReader;

    @Autowired
    private ResourceOwnerChecker ownerChecker;

    // =====================================================================
    // 装配
    // =====================================================================

    @Test
    @DisplayName("GrantableResourceProvider 三类受管资源全部注册，且每类恰好一个")
    void allThreeTypesRegisteredExactlyOnce() {
        assertThat(context.getBeansOfType(GrantableResourceProvider.class))
                .as("三类受管资源（契约 §2.5 穷举）各一个实现；多一个 = 有一份是死代码且不报错")
                .hasSize(3);
        assertThat(grantableReader.registeredTypes())
                .containsExactlyInAnyOrder(ResourceType.COURSE, ResourceType.QUESTION,
                        ResourceType.VIDEO);
    }

    @Test
    @DisplayName("同一类型注册两个实现 —— 启动即失败，不留到运行期按注入顺序抽签")
    void duplicateRegistrationFailsLoudly() {
        GrantableResourceProvider course = providerOf(ResourceType.COURSE);
        assertThatThrownBy(() -> new GrantableResourceReader(List.of(course, course)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注册了两个 GrantableResourceProvider");
    }

    @Test
    @DisplayName("未注册的类型响亮失败，不返回空页（空页 = 「你一个资源都没有」，接口 200 结果错）")
    void unregisteredTypeFailsLoudly() {
        GrantableResourceReader narrow =
                new GrantableResourceReader(List.of(providerOf(ResourceType.COURSE)));
        assertThatThrownBy(() -> narrow.page(ResourceType.VIDEO, new GrantableResourceQuery()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未注册");
    }

    // =====================================================================
    // 课程（resource_type = 1）
    // =====================================================================

    @Test
    @DisplayName("课程：自有 ∪ 传进来的清单；source=1/2 各自切一半")
    void coursePageSplitsOwnedAndGranted() throws Exception {
        courseFixtures.grantCourse(CourseFixtures.C_ROOT, CourseFixtures.TA, CourseFixtures.TENANT_ID);

        runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.TA, () -> {
            PageResult<GrantableResourceItem> all = page(ResourceType.COURSE, CourseFixtures.TA,
                    List.of(CourseFixtures.C_ROOT), null);
            assertThat(ids(all)).containsExactlyInAnyOrder(CourseFixtures.C_TA, CourseFixtures.C_ROOT);
            assertThat(sourceOf(all, CourseFixtures.C_TA)).isEqualTo(GrantableResourceItem.SOURCE_OWNED);
            assertThat(sourceOf(all, CourseFixtures.C_ROOT)).isEqualTo(GrantableResourceItem.SOURCE_GRANTED);

            assertThat(ids(page(ResourceType.COURSE, CourseFixtures.TA,
                    List.of(CourseFixtures.C_ROOT), GrantableResourceItem.SOURCE_OWNED)))
                    .containsExactly(CourseFixtures.C_TA);
            assertThat(ids(page(ResourceType.COURSE, CourseFixtures.TA,
                    List.of(CourseFixtures.C_ROOT), GrantableResourceItem.SOURCE_GRANTED)))
                    .containsExactly(CourseFixtures.C_ROOT);
        });
    }

    @Test
    @DisplayName("课程：source=2 且清单为空 → 空页；【绝不能退化成不加过滤】")
    void emptyRegrantableListMustNotFallBackToNoFilter() throws Exception {
        runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.TA, () -> {
            PageResult<GrantableResourceItem> page = page(ResourceType.COURSE, CourseFixtures.TA,
                    List.of(), GrantableResourceItem.SOURCE_GRANTED);
            assertThat(page.getTotal())
                    .as("一条可再下发的授权都没有 → 0 行。若这里出现 C_ROOT，"
                            + "说明空集分支被写成了「不加这个条件」——那会把全机构的课程列出来")
                    .isZero();
            assertThat(page.getList()).isEmpty();
        });
    }

    @Test
    @DisplayName("课程：extra 带齐 §9.1 的四个字段；keyword / subject 生效")
    void courseExtraAndFilters() throws Exception {
        runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.ROOT, () -> {
            GrantableResourceQuery query = query(CourseFixtures.ROOT, List.of(), null);
            query.setKeyword("ROOT 的");
            query.setSubject("数学");
            PageResult<GrantableResourceItem> page = grantableReader.page(ResourceType.COURSE, query);

            assertThat(ids(page)).containsExactly(CourseFixtures.C_ROOT);
            assertThat(page.getList().get(0).getExtra())
                    .containsOnlyKeys("subject", "status", "lessonCount", "totalDuration");
            assertThat(page.getList().get(0).getResourceName()).isEqualTo("ROOT 的课程");

            query.setSubject("英语");
            assertThat(grantableReader.page(ResourceType.COURSE, query).getTotal())
                    .as("subject 筛选必须真的生效")
                    .isZero();
        });
    }

    @Test
    @DisplayName("课程：namesOf 跳过跨租户与不存在的键（不是回 null 值）")
    void courseNamesOfOmitsInvisible() throws Exception {
        runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.ROOT, () -> {
            Map<Long, String> names = grantableReader.namesOf(ResourceType.COURSE,
                    List.of(CourseFixtures.C_ROOT, CourseFixtures.C_OTHER, 9L));
            assertThat(names).containsOnlyKeys(CourseFixtures.C_ROOT);
            assertThat(names.get(CourseFixtures.C_ROOT)).isEqualTo("ROOT 的课程");
        });
    }

    // =====================================================================
    // 视频（resource_type = 3）
    // =====================================================================

    @Test
    @DisplayName("视频：自有 ∪ 清单；已逻辑删除的媒资不出现")
    void videoPageExcludesDeleted() throws Exception {
        runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.ROOT, () -> {
            PageResult<GrantableResourceItem> page =
                    page(ResourceType.VIDEO, CourseFixtures.ROOT, List.of(), null);
            assertThat(ids(page))
                    .as("转码中的媒资【要】出现 —— 授权是提前铺权限，不是等转码完了再补授"
                            + "（契约 §2.5 规则 12 同源）；已逻辑删除的不出现")
                    .containsExactlyInAnyOrder(CourseFixtures.VIDEO_OK, CourseFixtures.VIDEO_TRANSCODING);
            assertThat(page.getList().get(0).getExtra())
                    .containsOnlyKeys("duration", "status", "sizeBytes");
        });
    }

    @Test
    @DisplayName("视频：namesOf 返回 video_name")
    void videoNamesOf() throws Exception {
        runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.ROOT, () -> assertThat(
                grantableReader.namesOf(ResourceType.VIDEO, List.of(CourseFixtures.VIDEO_OK)))
                .containsEntry(CourseFixtures.VIDEO_OK, "已转码视频"));
    }

    // =====================================================================
    // 批量归属查询 == 逐个查（课程与视频各一次）
    // =====================================================================

    @Test
    @DisplayName("ownerNodeIdsOf 与逐个 ownerNodeIdOf 逐字相同（键写反不会报错，只会判错 owner）")
    void batchOwnerLookupMatchesTheLoop() throws Exception {
        runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.ROOT, () -> {
            assertBatchMatchesLoop(ResourceType.COURSE,
                    List.of(CourseFixtures.C_ROOT, CourseFixtures.C_TA, CourseFixtures.C_OTHER, 9L));
            assertBatchMatchesLoop(ResourceType.VIDEO,
                    List.of(CourseFixtures.VIDEO_OK, CourseFixtures.VIDEO_TRANSCODING,
                            CourseFixtures.VIDEO_DELETED, 9L));
        });
    }

    private void assertBatchMatchesLoop(ResourceType type, List<Long> ids) {
        ResourceOwnerProvider provider = ownerProviderOf(type);
        Map<Long, Long> expected = new HashMap<>();
        for (Long id : ids) {
            Long owner = provider.ownerNodeIdOf(id);
            if (owner != null) {
                expected.put(id, owner);
            }
        }
        assertThat(provider.ownerNodeIdsOf(ids))
                .as("%s 的批量归属查询与逐个查不一致 —— 覆写的理由只有性能，语义必须逐字相同", type)
                .containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(expected)
                .as("这条断言本身不能空转：至少要有一个查得到的资源")
                .isNotEmpty();
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    private PageResult<GrantableResourceItem> page(ResourceType type, long myNodeId,
                                                   List<Long> regrantableIds, Integer source) {
        return grantableReader.page(type, query(myNodeId, regrantableIds, source));
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

    private static int sourceOf(PageResult<GrantableResourceItem> page, long resourceId) {
        return page.getList().stream()
                .filter(item -> item.getResourceId() == resourceId)
                .findFirst().orElseThrow().getSource();
    }

    private GrantableResourceProvider providerOf(ResourceType type) {
        return context.getBeansOfType(GrantableResourceProvider.class).values().stream()
                .filter(provider -> provider.resourceType() == type)
                .findFirst().orElseThrow();
    }

    private ResourceOwnerProvider ownerProviderOf(ResourceType type) {
        return context.getBeansOfType(ResourceOwnerProvider.class).values().stream()
                .filter(provider -> provider.resourceType() == type)
                .findFirst().orElseThrow();
    }
}
