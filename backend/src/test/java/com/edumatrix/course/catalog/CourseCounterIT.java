package com.edumatrix.course.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.course.CourseCounterRefresher;
import com.edumatrix.course.catalog.dto.LessonCreateReq;
import com.edumatrix.course.catalog.service.LessonService;
import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冗余计数 {@code lesson_count} / {@code total_duration}（PRD F2-1 规则 7）。
 *
 * <h2>为什么专门写一个类</h2>
 * <p>这一类冗余计数在本项目已经出过两次问题（{@code org_node.child_count/student_count}、
 * {@code org_teacher.student_count}）。它的失效形态是<b>接口 200、字段齐全、数字是错的</b> ——
 * 1 号失败模式，不测就永远发现不了。
 *
 * <h2>{@link #concurrentLessonCreationKeepsCountExact} 是课程行锁的存在性证明</h2>
 * <p>20 个线程并发建课时，最终 {@code lesson_count} 必须恰好是 20。
 * 去掉 {@code CourseAccessGuard#loadOwnedForUpdate} 里的 {@code FOR UPDATE}，
 * 多个事务会读到同一份中间态、各自算出偏小的 {@code COUNT} 并互相覆盖 —— 本条立刻红。
 */
class CourseCounterIT extends CourseIntegrationTestBase {

    @Autowired
    private LessonService lessonService;

    @Autowired
    private CourseCounterRefresher counterRefresher;

    @Test
    @DisplayName("增删课时后两个冗余列与真实值一致（全量重算，不是增量 ±1）")
    void countersFollowLessonChanges() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token);

        long videoLesson = createLesson(token, chapter, 1, CourseFixtures.VIDEO_OK, null, 1);
        assertEquals(1, courseFixtures.lessonCountOf(CourseFixtures.C_ROOT));
        assertEquals(CourseFixtures.VIDEO_OK_DURATION,
                courseFixtures.totalDurationOf(CourseFixtures.C_ROOT));

        long materialId = createMaterial(token);
        createLesson(token, chapter, 2, null, materialId, 1);
        assertEquals(2, courseFixtures.lessonCountOf(CourseFixtures.C_ROOT));
        assertEquals(CourseFixtures.VIDEO_OK_DURATION,
                courseFixtures.totalDurationOf(CourseFixtures.C_ROOT), "图文课时 duration=0");

        deleteWithToken("/api/v1/course/lessons/" + videoLesson, token);
        assertEquals(1, courseFixtures.lessonCountOf(CourseFixtures.C_ROOT));
        assertEquals(0, courseFixtures.totalDurationOf(CourseFixtures.C_ROOT));
    }

    @Test
    @DisplayName("C 定案：lesson_count 计【全部未删除课时】，隐藏课时也算在内")
    void hiddenLessonsAreCounted() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token);
        createLesson(token, chapter, 1, CourseFixtures.VIDEO_OK, null, 1);
        createLesson(token, chapter, 1, CourseFixtures.VIDEO_TRANSCODING, null, 0);

        assertEquals(2, courseFixtures.lessonCountOf(CourseFixtures.C_ROOT),
                "C 定案取 DDL 字面（无 status 限定）。学生端 §6.1 的 lessonCount 由模块 14 现算可见数，"
                        + "不读这一列 —— 两个口径分属两处，各自不说谎");
    }

    @Test
    @DisplayName("人为把冗余列改错，下一次课时变更即自愈（全量重算相对增量的唯一好处）")
    void wrongCounterSelfHealsOnNextChange() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token);
        createLesson(token, chapter, 2, null, createMaterial(token), 1);

        jdbcTemplate.update("UPDATE crs_course SET lesson_count = 999, total_duration = 999 WHERE id = ?",
                CourseFixtures.C_ROOT);
        createLesson(token, chapter, 1, CourseFixtures.VIDEO_OK, null, 1);

        assertEquals(2, courseFixtures.lessonCountOf(CourseFixtures.C_ROOT));
        assertEquals(CourseFixtures.VIDEO_OK_DURATION,
                courseFixtures.totalDurationOf(CourseFixtures.C_ROOT));
    }

    @Test
    @DisplayName("CourseCounterRefresher#refreshByVideo：模块 09 的转码事件将经它刷新，本模块先自测")
    void refreshByVideoUpdatesLessonAndCourse() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token);
        long lessonId = createLesson(token, chapter, 1, CourseFixtures.VIDEO_OK, null, 1);
        assertEquals(CourseFixtures.VIDEO_OK_DURATION, courseFixtures.lessonDurationOf(lessonId));

        int newDuration = 1234;
        runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.ROOT, () -> {
            int affected = counterRefresher.refreshByVideo(CourseFixtures.VIDEO_OK, newDuration);
            assertEquals(1, affected);
        });

        assertEquals(newDuration, courseFixtures.lessonDurationOf(lessonId),
                "crs_lesson.duration 的写入点只有 CourseCounterService 一处；"
                        + "模块 09 不得自己 UPDATE crs_lesson");
        assertEquals(newDuration, courseFixtures.totalDurationOf(CourseFixtures.C_ROOT),
                "课程 total_duration 必须跟着变 —— 否则就是「课时时长对了、课程总时长没对」");
    }

    @Test
    @DisplayName("课程行锁：20 个线程并发建课时，lesson_count 恰好 20（去掉 FOR UPDATE 即红）")
    void concurrentLessonCreationKeepsCountExact() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token);

        int threads = 20;
        AtomicInteger succeeded = new AtomicInteger();
        runAsTestUser(CourseFixtures.TENANT_ID, CourseFixtures.ROOT, () -> {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                tasks.add(() -> {
                    start.await();
                    LessonCreateReq req = new LessonCreateReq();
                    req.setChapterId(chapter);
                    req.setLessonName("并发课时" + index);
                    req.setLessonType(1);
                    req.setVideoId(CourseFixtures.VIDEO_OK);
                    req.setStatus(1);
                    lessonService.create(req);
                    succeeded.incrementAndGet();
                    return null;
                });
            }
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        });

        assertEquals(threads, succeeded.get());
        assertEquals(threads, courseFixtures.liveLessonCount(CourseFixtures.C_ROOT));
        assertEquals(threads, courseFixtures.lessonCountOf(CourseFixtures.C_ROOT),
                "冗余计数与真实行数对不上 —— 课程行锁没生效，"
                        + "多个事务读到了同一份中间态并互相覆盖");
        assertEquals(threads * CourseFixtures.VIDEO_OK_DURATION,
                courseFixtures.totalDurationOf(CourseFixtures.C_ROOT));
    }

    // =====================================================================

    private long createChapter(String token) throws Exception {
        JsonNode created = client.postWithToken("/api/v1/course/chapters", token,
                "{\"courseId\":\"" + CourseFixtures.C_ROOT + "\",\"parentId\":\"0\","
                        + "\"chapterName\":\"第一章\"}");
        assertEquals(200, code(created), created.toString());
        return data(created).path("id").asLong();
    }

    /** 走夹具而不是调资料接口：本类验的是课时，资料只是个被引用对象。 */
    private long createMaterial(String token) {
        long materialId = 1968000000000008202L;
        courseFixtures.material(materialId, "讲义", "<p>正文</p>",
                CourseFixtures.ROOT, CourseFixtures.TENANT_ID);
        return materialId;
    }

    private long createLesson(String token, long chapterId, int lessonType,
                              Long videoId, Long materialId, int status) throws Exception {
        JsonNode created = client.postWithToken("/api/v1/course/lessons", token,
                "{\"chapterId\":\"" + chapterId + "\",\"lessonName\":\"课时\","
                        + "\"lessonType\":" + lessonType
                        + (videoId == null ? "" : ",\"videoId\":\"" + videoId + "\"")
                        + (materialId == null ? "" : ",\"materialId\":\"" + materialId + "\"")
                        + ",\"status\":" + status + "}");
        assertEquals(200, code(created), created.toString());
        return data(created).path("id").asLong();
    }
}
