package com.edumatrix.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.edumatrix.common.tenant.CurrentContextProvider;
import com.edumatrix.common.tenant.EduMatrixTenantLineHandler;
import com.edumatrix.common.tenant.NoSessionCurrentContextProvider;
import com.edumatrix.common.tenant.TenantHelper;

/**
 * 租户上下文装配。
 *
 * <p>{@link CurrentContextProvider} 标了 {@link ConditionalOnMissingBean} ——
 * <b>模块 02 只要注册一个自己的实现 Bean，本默认实现自动让位</b>，
 * 不需要改本类，也不需要改 {@code common} 下的任何代码。
 *
 * <p>租户拦截器本身在 {@code MybatisPlusConfig} 里装，因为它是 MyBatis 拦截器链的一环。
 */
@Configuration
public class TenantConfig {

    /**
     * 无会话时的默认上下文来源。模块 02 用读 Sa-Token Session 的实现覆盖它。
     */
    @Bean
    @ConditionalOnMissingBean(CurrentContextProvider.class)
    public CurrentContextProvider currentContextProvider() {
        return new NoSessionCurrentContextProvider();
    }

    /**
     * 把上下文来源装进 {@link TenantHelper} 的静态门面。
     *
     * <p>用 {@code @Bean} 方法而不是 {@code @PostConstruct}，是为了让它的执行时机
     * 明确落在 {@code currentContextProvider} 之后 —— Spring 按参数依赖排序，
     * 不依赖任何隐式的初始化顺序。
     */
    @Bean
    public TenantHelperInitializer tenantHelperInitializer(CurrentContextProvider provider) {
        TenantHelper.setProvider(provider);
        return new TenantHelperInitializer();
    }

    @Bean
    public EduMatrixTenantLineHandler tenantLineHandler() {
        return new EduMatrixTenantLineHandler();
    }

    /** 仅作为「静态门面已装配」的标记，无行为。 */
    public static class TenantHelperInitializer {
    }
}
