package com.edumatrix.org.node.service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.edumatrix.common.subtree.NodeNameReader;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;

/**
 * {@code common/subtree/NodeNameReader} 的实现：把 {@code org} 领域的节点名
 * 暴露给 {@code course}（模块 08 的 {@code ownerNodeName}）。
 *
 * <p>与 {@code auth/session/AuthAccountProvider} 同构 —— 接口在 {@code common/}、
 * 实现在提供方领域内、消费方按接口注入。跨领域依赖为零，约定检查③ 零命中。
 *
 * <p><b>本类不含任何判定</b>，只是一层委派。租户条件由插件注入。
 */
@Component
public class OrgNodeNameProvider implements NodeNameReader {

    private final OrgNodeMapper nodeMapper;

    public OrgNodeNameProvider(OrgNodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    @Override
    public Map<Long, String> nodeNames(Collection<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<OrgNode> nodes = nodeMapper.selectByIds(nodeIds.stream().distinct().toList());
        Map<Long, String> names = new LinkedHashMap<>();
        for (OrgNode node : nodes) {
            names.put(node.getId(), node.getNodeName());
        }
        return names;
    }
}
