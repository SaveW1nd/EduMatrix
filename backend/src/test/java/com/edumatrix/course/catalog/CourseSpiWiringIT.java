package com.edumatrix.course.catalog;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.edumatrix.common.account.UserNameReader;
import com.edumatrix.common.course.CourseCounterRefresher;
import com.edumatrix.common.course.LessonVisibilityChecker;
import com.edumatrix.common.file.FileMetaReader;
import com.edumatrix.common.file.InlineFileUrlProvider;
import com.edumatrix.common.media.VideoRefReader;
import com.edumatrix.common.course.LessonVideoRefCounter;
import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceOwnerProvider;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.common.subtree.NodeNameReader;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨领域 SPI 的装配自检 —— <b>每个能力有且只有一个实现</b>。
 *
 * <h2>这几条是「到期标记」的执行侧</h2>
 * <p>模块 08 开了一个<b>临时</b>构件 {@link TempVideoRefReader}（读 {@code vod_video}），
 * 到期动作写在 {@code 04-实施计划.md} §B 模块 09 的「做完什么算做完」里。
 * 光靠文档标记不够 —— F 清单没人逐条回看，这就是本项目反复点名的失败模式。
 * {@link #videoRefReaderHasExactlyOneImplementation} 是它的机器守卫：
 * <ul>
 *   <li>模块 09 <b>只加不删</b> → Bean 变两个 → <b>立刻红</b>；
 *   <li>模块 09 <b>删了没加</b> → 上下文启动失败 → 更早红。
 * </ul>
 *
 * <h2>为什么连「只有一个实现」也要断言</h2>
 * <p>两份同源实现是本项目的 4 号失败模式。{@code @Autowired} 单个 Bean 在有两个候选时
 * 会启动失败，但一旦有人给其中一个加了 {@code @Primary}，另一个就变成<b>永远不会被调用
 * 却仍然存在</b>的死实现 —— 而它不会报错。按 <b>Bean 数量</b>断言才拦得住。
 */
class CourseSpiWiringIT extends CourseIntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ResourceOwnerChecker resourceOwnerChecker;

    /** 手工构造窄注册表时要传它 —— 本条只验分发，不验授权，传真的即可。 */
    @Autowired
    private ResourceGrantReader grantReader;

    @Test
    @DisplayName("VideoRefReader 恰好一个实现 —— 模块 09 到期删 TempVideoRefReader 的机器守卫")
    void videoRefReaderHasExactlyOneImplementation() {
        Map<String, VideoRefReader> beans = context.getBeansOfType(VideoRefReader.class);
        assertEquals(1, beans.size(),
                "VideoRefReader 的实现不是一个：" + beans.keySet()
                        + "。模块 09 落地时必须【删掉】common/media/TempVideoRefReader 与 "
                        + "common/media/mapper/VideoRefMapper，而不是再加一个");
        assertEquals("VodVideoRefProvider", beans.values().iterator().next().getClass().getSimpleName(),
                "VideoRefReader 的实现不是模块 09 的正式实现 —— 临时构件 TempVideoRefReader "
                        + "与 common/media/mapper/VideoRefMapper 已随模块 09 删除，不该再出现");
    }

    @Test
    @DisplayName("模块 08 对外产出的两个能力各自恰好一个实现")
    void moduleOutputsHaveExactlyOneImplementation() {
        assertEquals(1, context.getBeansOfType(LessonVisibilityChecker.class).size());
        assertEquals(1, context.getBeansOfType(CourseCounterRefresher.class).size());
    }

    @Test
    @DisplayName("模块 08 依赖的四个跨领域 SPI 各自恰好一个实现（接口在 common/、实现在提供方领域内）")
    void consumedSpisHaveExactlyOneImplementation() {
        assertEquals(1, context.getBeansOfType(InlineFileUrlProvider.class).size(), "system/file");
        assertEquals(1, context.getBeansOfType(FileMetaReader.class).size(), "system/file");
        assertEquals(1, context.getBeansOfType(NodeNameReader.class).size(), "org/node");
        assertEquals(1, context.getBeansOfType(UserNameReader.class).size(), "system/user");
        assertEquals(1, context.getBeansOfType(CurrentNodeProvider.class).size(), "org/node");
    }

    // 方法名保留 onlyCourseOwnerProviderRegistered 而不改成 registeredOwnerProviders：
    // 模块 09 后合，它要改的正是下面这两行。改方法名会让那次 rebase 的冲突从
    // 「一处两行」变成「整个方法块」，而这条断言的真实含义已由 @DisplayName 承载。
    @Test
    @DisplayName("ResourceOwnerProvider 三类受管资源全部注册（COURSE + QUESTION + VIDEO）")
    void onlyCourseOwnerProviderRegistered() {
        assertEquals(3, context.getBeansOfType(ResourceOwnerProvider.class).size());
        assertEquals(java.util.Set.of(ResourceType.COURSE, ResourceType.QUESTION, ResourceType.VIDEO),
                resourceOwnerChecker.registeredTypes(),
                "已注册的资源类型变了。契约 §2.5 穷举三类受管资源：课程(1) 题目(2) 视频(3)，"
                        + "现已全部补齐（模块 08 / 10 / 09）。将来新增第四类时改这里");
    }

    /**
     * 未注册的类型必须<b>响亮失败</b>，不静默返回 {@code false}。
     *
     * <h2>⚠ 本条的探针换了，因为原来那个已经不存在了（F-66）</h2>
     * <p>原写法是拿容器里的 {@code resourceOwnerChecker} 去问 {@code VIDEO} ——
     * 那时它还没被注册。而 {@link ResourceType} <b>穷举只有三个值</b>
     * （契约 §2.5：课程 / 题目 / 视频），模块 08 / 10 / 09 补齐之后
     * <b>再也没有"未注册的类型"可探</b>，这条断言会随最后一个提供方落地而自然失效。
     *
     * <p>而它守的东西没有失效：「未注册即响亮失败」这条纪律管的是<b>将来新增</b>的资源类型，
     * 静默返回 {@code false} 会让授权引擎判定「你不是 owner」而接口 200、字段齐全、结果错
     * （本项目 1 号失败模式）。守卫不能随被守对象补齐而消失 ——
     * 那正是「以为存在、实际从未生效的保障」的又一条产生路径。
     *
     * <p>故改为<b>手工构造一个只装了 {@code COURSE} 的注册表</b>再问 {@code VIDEO}：
     * 不依赖"还有哪个类型没注册"，永远可测；断言的仍是同一件事。
     */
    @Test
    @DisplayName("未注册的类型响亮失败，不静默返回 false（探针不再依赖『还有类型没注册』）")
    void unregisteredTypeStillFailsLoudly() {
        ResourceOwnerProvider courseOnly = context.getBeansOfType(ResourceOwnerProvider.class)
                .values().stream()
                .filter(provider -> provider.resourceType() == ResourceType.COURSE)
                .findFirst().orElseThrow();
        ResourceOwnerChecker narrow =
                new ResourceOwnerChecker(java.util.List.of(courseOnly), grantReader);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> narrow.isOwner(ResourceType.VIDEO, 1L, 1L));
        assertTrue(error.getMessage().contains("尚未注册"), error.getMessage());
        assertTrue(error.getMessage().contains("响亮失败"), error.getMessage());
    }

    /**
     * 模块 09 新增的跨领域 SPI（F-62）：{@code vod} 不能 import {@code course}（检查③），
     * 而媒资列表的 {@code refLessonCount} 与删除拦截的 {@code 20016} 都要读 {@code crs_lesson}。
     */
    @Test
    @DisplayName("LessonVideoRefCounter 恰好一个实现（模块 09 消费，实现在 course/catalog）")
    void lessonVideoRefCounterHasExactlyOneImplementation() {
        assertEquals(1, context.getBeansOfType(LessonVideoRefCounter.class).size());
    }
}
