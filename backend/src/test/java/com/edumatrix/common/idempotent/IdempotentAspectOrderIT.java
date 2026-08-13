package com.edumatrix.common.idempotent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;

import com.edumatrix.support.IntegrationTest;

/**
 * 幂等切面必须排在 {@code @Transactional} 的<b>外层</b>。
 *
 * <p>本测试钉的是一个<b>不会以任何其他方式暴露</b>的不变量：两个切面若都取默认
 * {@link Ordered#LOWEST_PRECEDENCE}，谁在外层是未定义的，而顺序错了的表现是
 * 「Redis 里缓存了一个被回滚掉的结果」—— 客户端重试拿到成功、库里什么都没发生，
 * 没有任何异常、没有任何日志。
 */
@IntegrationTest
class IdempotentAspectOrderIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("@Order 注解在场，且值与常量一致（删掉注解要红）")
    void orderAnnotationIsPresent() {
        Order order = AnnotationUtils.findAnnotation(IdempotentAspect.class, Order.class);

        assertThat(order)
                .as("没有 @Order 就等于把「幂等切面在事务外层」交给运气")
                .isNotNull();
        assertThat(order.value()).isEqualTo(IdempotentAspect.ORDER);
    }

    @Test
    @DisplayName("幂等切面的 order 小于事务切面 —— Spring 中 order 越小越靠外")
    void idempotentRunsOutsideTransaction() {
        BeanFactoryTransactionAttributeSourceAdvisor txAdvisor =
                applicationContext.getBean(BeanFactoryTransactionAttributeSourceAdvisor.class);

        assertThat(IdempotentAspect.ORDER)
                .as("幂等缓存的是「已提交的结果」。排在事务内层时，proceed() 返回时事务尚未提交，"
                        + "而 catch(Throwable) 兜不住 setRollbackOnly 这条不抛异常的回滚路径")
                .isLessThan(txAdvisor.getOrder());
        assertThat(txAdvisor.getOrder())
                .as("事务切面用的确实是默认的 LOWEST_PRECEDENCE —— 若它被改过，本测试的前提要重新评估")
                .isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
