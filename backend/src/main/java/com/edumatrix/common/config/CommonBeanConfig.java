package com.edumatrix.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.edumatrix.common.idempotent.IdempotentAspect;
import com.edumatrix.common.subtree.NodeAncestorCache;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.subtree.mapper.OrgNodeSubtreeMapper;
import com.edumatrix.common.tenant.CurrentContextProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 公共层里那些「不属于某个专门配置类」的 Bean。
 *
 * <p>{@code GlobalExceptionHandler} 不在这里 —— {@code @RestControllerAdvice} 本身就带
 * {@code @Component} 元注解，组件扫描会拿到它。而 {@code @Aspect} 不带，所以
 * {@link IdempotentAspect} 必须在这里显式注册。
 */
@Configuration
public class CommonBeanConfig {

    @Bean
    public NodeAncestorCache nodeAncestorCache(StringRedisTemplate redisTemplate,
                                               OrgNodeSubtreeMapper nodeMapper) {
        return new NodeAncestorCache(redisTemplate, nodeMapper);
    }

    @Bean
    public SubtreeScopeHelper subtreeScopeHelper(OrgNodeSubtreeMapper nodeMapper,
                                                 NodeAncestorCache ancestorCache,
                                                 CurrentContextProvider contextProvider) {
        return new SubtreeScopeHelper(nodeMapper, ancestorCache, contextProvider);
    }

    /**
     * @param waitMillis 等待「首次请求」写出结果的窗口。00-通用约定 §7.2 只定义了
     *                   Redis key 与 5 分钟 TTL，<b>没有定义这个值</b>，故做成配置项。
     */
    @Bean
    public IdempotentAspect idempotentAspect(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${edumatrix.idempotent.wait-millis:1000}") long waitMillis) {
        return new IdempotentAspect(redisTemplate, objectMapper, waitMillis);
    }
}
