package com.edumatrix.question.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.edumatrix.common.question.QuestionVersionService;
import com.edumatrix.common.question.QuestionVisibilityChecker;
import com.edumatrix.common.resource.ResourceOwnerProvider;
import com.edumatrix.question.support.QuestionIntegrationTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模块 10 对外产出的装配自检 —— <b>每个能力有且只有一个实现</b>。
 *
 * <h2>为什么连「只有一个实现」也要断言</h2>
 * <p>两份同源实现是本项目的 4 号失败模式。{@code @Autowired} 单个 Bean 在有两个候选时
 * 会启动失败，但一旦有人给其中一个加了 {@code @Primary}，另一个就变成
 * <b>永远不会被调用却仍然存在</b>的死实现 —— 而它不会报错。
 * 按 <b>Bean 数量</b>断言才拦得住。与 {@code CourseSpiWiringIT} 同型。
 *
 * <h2>{@code AutoGrader} / {@code AnswerJson} 为什么不在这里</h2>
 * <p>它们是无状态的静态工具，不是 Bean，数不出来。
 * {@code AutoGrader} 在模块 10 <b>没有生产调用方</b>（判卷是模块 15），
 * 它的验证全部在 {@code AutoGraderTest}。这一点在 {@code AutoGrader} 类注释里也写了 ——
 * 与其让下一个人以为它在跑，不如写清楚它没有。
 */
class QuestionSpiWiringIT extends QuestionIntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("模块 10 对外产出的两个 SPI 各自恰好一个实现")
    void moduleOutputsHaveExactlyOneImplementation() {
        assertEquals(1, context.getBeansOfType(QuestionVisibilityChecker.class).size(),
                "QuestionVisibilityChecker 的实现不是一个 —— 「我能看到哪些题」是全系统唯一口径");
        assertEquals(1, context.getBeansOfType(QuestionVersionService.class).size(),
                "QuestionVersionService 的实现不是一个 —— 版本快照的读侧只能有一个真相源");
    }

    @Test
    @DisplayName("ResourceOwnerProvider 共两个：COURSE(模块 08) + QUESTION(模块 10)，VIDEO 待模块 09")
    void ownerProvidersAreCourseAndQuestion() {
        assertEquals(2, context.getBeansOfType(ResourceOwnerProvider.class).size(),
                "模块 09 补 VIDEO 时这里应变成 3 —— 本条与 CourseSpiWiringIT 那条互为镜像");
    }
}
