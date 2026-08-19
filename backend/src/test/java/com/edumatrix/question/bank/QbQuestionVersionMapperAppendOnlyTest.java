package com.edumatrix.question.bank;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.question.bank.mapper.QbQuestionVersionMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code qb_question_version} 只增不改的<b>编译期守卫的执行侧</b>。
 *
 * <h2>为什么这条测试不是多余的</h2>
 * <p>「历史版本不可修改、不可删除」（契约 §4 版本规则、PRD F3-2 规则 3）的第一道守卫
 * 是编译期的：{@link QbQuestionVersionMapper} 不继承 {@code BaseMapper}，
 * 所以 {@code updateById} / {@code deleteById} <b>这两个方法不存在</b>，
 * 写出来编译不过。
 *
 * <p>但编译期护栏拦不住「下一个人给它加上 {@code extends BaseMapper}」——
 * 那一步不报错，而它一旦发生，四个写方法立刻白送回来，
 * 「不可修改」就退回成一句注释。本测试与 {@code check_backend_conventions.sh}
 * 检查 ⑦ 是同一件事的两个执行点：这条在 {@code mvn test} 里红，那条在检查脚本里红。
 * 两处都要有，因为两处的读者不同 —— 改代码的人跑测试，改脚本的人跑脚本。
 *
 * <p><b>变异验证</b>：给 {@link QbQuestionVersionMapper} 加
 * {@code extends BaseMapper<QbQuestionVersion>} → {@link #mapperExtendsNothing} 红。
 */
class QbQuestionVersionMapperAppendOnlyTest {

    /** 写动作的方法名前缀（穷举 MyBatis-Plus {@code BaseMapper} 白送的那几个）。 */
    private static final List<String> WRITE_PREFIXES = List.of("update", "delete", "remove");

    @Test
    @DisplayName("窄 Mapper 不继承任何接口 —— extends BaseMapper 会白送 updateById/deleteById")
    void mapperExtendsNothing() {
        Class<?>[] parents = QbQuestionVersionMapper.class.getInterfaces();
        assertEquals(0, parents.length,
                "QbQuestionVersionMapper 继承了 " + Arrays.toString(parents)
                        + " —— 只要继承 BaseMapper，updateById / update / deleteById / delete "
                        + "四个方法立刻可用，而契约 §4 与 PRD F3-2 规则 3 要求"
                        + "「历史版本不可修改、不可删除、无任何更新入口（含管理员）」。"
                        + "这道守卫的全部价值就在于「那个方法不存在」");
    }

    @Test
    @DisplayName("窄 Mapper 声明的方法里没有任何写动作（append 是唯一的写入口）")
    void declaresNoWriteMethodExceptAppend() {
        for (Method method : QbQuestionVersionMapper.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            assertFalse(WRITE_PREFIXES.stream().anyMatch(name::startsWith),
                    "出现了写方法 " + method.getName() + "()：版本快照只允许 append");
            assertFalse(hasWriteAnnotation(method),
                    method.getName() + "() 带了 @Update / @Delete 注解");
        }
        assertTrue(Arrays.stream(QbQuestionVersionMapper.class.getDeclaredMethods())
                        .anyMatch(m -> m.getName().equals("append")),
                "append() 不见了 —— 本测试正在空转，确认是否被改名");
    }

    private static boolean hasWriteAnnotation(Method method) {
        return Arrays.stream(method.getAnnotations())
                .map(a -> a.annotationType().getSimpleName())
                .anyMatch(n -> n.equals("Update") || n.equals("Delete"));
    }
}
