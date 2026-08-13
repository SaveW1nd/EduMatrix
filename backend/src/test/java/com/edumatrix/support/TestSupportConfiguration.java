package com.edumatrix.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.edumatrix.common.tenant.TenantHelper;

/**
 * 测试上下文的补充装配。
 *
 * <p>{@link TestCurrentContextProvider} 标 {@link Primary}，压过
 * {@code TenantConfig} 里那个 {@code @ConditionalOnMissingBean} 的默认实现 ——
 * 这也顺带验证了「模块 02 只要注册一个自己的实现 Bean，默认实现就自动让位」这条约定成立。
 */
@TestConfiguration
public class TestSupportConfiguration {

    @Bean
    @Primary
    public TestCurrentContextProvider testCurrentContextProvider() {
        TestCurrentContextProvider provider = new TestCurrentContextProvider();
        // 静态门面同步指向它，否则 TenantHelper 仍读默认实现
        TenantHelper.setProvider(provider);
        return provider;
    }
}
