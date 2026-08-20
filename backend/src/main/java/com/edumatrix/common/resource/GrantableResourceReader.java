package com.edumatrix.common.resource;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.edumatrix.common.response.PageResult;

/**
 * {@link GrantableResourceProvider} 的注册表 —— 按 {@link ResourceType} 分发。
 *
 * <p>纪律逐条照抄 {@link ResourceOwnerChecker}，因为踩的是同两个坑：
 * <ul>
 *   <li><b>同一类型注册两个实现 → 启动即失败</b>。不留到运行期按注入顺序抽签 ——
 *       那时两份实现里只有一份生效，另一份是<b>永远不会被调用却仍然存在</b>的死代码，
 *       而它不会报错（本项目 4 号失败模式）。
 *   <li><b>未注册的类型抛异常，不返回空页</b>。返回空页的表现是「这一类资源你一个都没有」——
 *       接口 200、字段齐全、结果错（本项目 1 号失败模式）。
 * </ul>
 */
@Component
public class GrantableResourceReader {

    private final Map<ResourceType, GrantableResourceProvider> providers =
            new EnumMap<>(ResourceType.class);

    public GrantableResourceReader(List<GrantableResourceProvider> registered) {
        for (GrantableResourceProvider provider : registered) {
            GrantableResourceProvider previous = providers.put(provider.resourceType(), provider);
            if (previous != null) {
                throw new IllegalStateException("resourceType=" + provider.resourceType()
                        + " 注册了两个 GrantableResourceProvider：" + previous.getClass().getName()
                        + " 与 " + provider.getClass().getName()
                        + "。每类资源只能有一个可授权清单的真相源");
            }
        }
    }

    /** 已注册的资源类型（只读视图），供启动自检与测试钉住。 */
    public Set<ResourceType> registeredTypes() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    /** 分页查「我可授权的资源」。 */
    public PageResult<GrantableResourceItem> page(ResourceType type, GrantableResourceQuery query) {
        return provider(type).page(query);
    }

    /** 批量取资源展示名。空集合直接返回空 Map，不惊动实现方。 */
    public Map<Long, String> namesOf(ResourceType type, Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        return provider(type).namesOf(resourceIds);
    }

    private GrantableResourceProvider provider(ResourceType type) {
        GrantableResourceProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalStateException("resourceType=" + type
                    + " 尚未注册 GrantableResourceProvider。每类受管资源必须由其所属领域注册一个提供方；"
                    + "此处【响亮失败】，而不是返回空页制造一次静默的「你一个资源都没有」"
                    + "（接口 200、字段齐全、结果错）");
        }
        return provider;
    }
}
