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
import com.edumatrix.common.media.TempVideoRefReader;
import com.edumatrix.common.media.VideoRefReader;
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

    @Test
    @DisplayName("VideoRefReader 恰好一个实现 —— 模块 09 到期删 TempVideoRefReader 的机器守卫")
    void videoRefReaderHasExactlyOneImplementation() {
        Map<String, VideoRefReader> beans = context.getBeansOfType(VideoRefReader.class);
        assertEquals(1, beans.size(),
                "VideoRefReader 的实现不是一个：" + beans.keySet()
                        + "。模块 09 落地时必须【删掉】common/media/TempVideoRefReader 与 "
                        + "common/media/mapper/VideoRefMapper，而不是再加一个");
        assertTrue(beans.values().iterator().next() instanceof TempVideoRefReader,
                "模块 09 已接管？那请把本条改成断言 VodVideoRefProvider，并删掉临时构件");
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
    @DisplayName("ResourceOwnerProvider 已注册 COURSE + QUESTION —— 模块 09 补 VIDEO 时本条会提醒改")
    void onlyCourseOwnerProviderRegistered() {
        assertEquals(2, context.getBeansOfType(ResourceOwnerProvider.class).size());
        assertEquals(java.util.Set.of(ResourceType.COURSE, ResourceType.QUESTION),
                resourceOwnerChecker.registeredTypes(),
                "已注册的资源类型变了：模块 09 补 VIDEO(3) 时请把这里改成 3 与 "
                        + "Set.of(COURSE, QUESTION, VIDEO)。模块 10 已补 QUESTION(2)");
    }

    @Test
    @DisplayName("未注册的类型在真实上下文里同样响亮失败，不静默返回 false")
    void unregisteredTypeStillFailsLoudly() {
        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> resourceOwnerChecker.isOwner(ResourceType.VIDEO, 1L, 1L));
        assertTrue(error.getMessage().contains("模块 09"), error.getMessage());
    }
}
