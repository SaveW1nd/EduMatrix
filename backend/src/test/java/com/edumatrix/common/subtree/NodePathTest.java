package com.edumatrix.common.subtree;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ancestors} 的两处易错点：<b>首位哨兵</b>与<b>空串分支</b>（契约 §2.4 / §2.9）。
 */
class NodePathTest {

    @Test
    @DisplayName("空串分支不可省：平台根的前缀是 '0' 而不是 ',0'")
    void rootPrefixMustNotBeCommaPrefixed() {
        NodePath root = new NodePath(0L, -1L, "", NodePath.NODE_TYPE_PLATFORM, 0L);

        assertThat(root.selfPrefix())
                .as("直接 CONCAT 会得到 ',0'，而机构根节点的 ancestors='0' "
                        + "既不等于 ',0' 也不 LIKE ',0,%%' —— 超管取全平台会静默返回空集")
                .isEqualTo("0");
    }

    @Test
    @DisplayName("机构根节点的前缀是 '0,机构id'，能被前缀 LIKE 命中")
    void orgRootPrefix() {
        NodePath orgRoot = new NodePath(1953827104412590001L, 0L, "0", NodePath.NODE_TYPE_ADMIN,
                1953827104412590001L);
        assertThat(orgRoot.selfPrefix()).isEqualTo("0,1953827104412590001");

        // 直接子节点的 ancestors 恰好等于 P（后面没有逗号）——
        // 只写 LIKE 会漏掉整层直接子节点，所以 SQL 里必须写 ancestors = P OR ancestors LIKE ...
        String directChildAncestors = "0,1953827104412590001";
        assertThat(directChildAncestors).isEqualTo(orgRoot.selfPrefix());
    }

    @Test
    @DisplayName("解析 ancestors 时跳过首位哨兵 0")
    void sentinelIsSkipped() {
        assertThat(NodePath.parseAncestorIds("0,100,101,205"))
                .as("首位 0 是平台根哨兵，不是可读节点 —— "
                        + "按 IN 查名称时返回行数比 id 数少 1 是正确行为，不是 bug")
                .containsExactly(100L, 101L, 205L);
    }

    @Test
    @DisplayName("平台根自身的 ancestors 是空串，祖先列表为空")
    void rootHasNoAncestors() {
        assertThat(NodePath.parseAncestorIds("")).isEmpty();
        assertThat(NodePath.parseAncestorIds(null)).isEmpty();
    }

    @Test
    @DisplayName("机构根节点的祖先链去掉哨兵后为空 —— 面包屑口径是「自租户根到自身」")
    void orgRootAncestorsAreEmptyAfterSkippingSentinel() {
        assertThat(NodePath.parseAncestorIds("0"))
                .as("平台根不属于任何租户，出现在租户的面包屑里反而是越界")
                .isEmpty();
    }

    @Test
    @DisplayName("逗号收边：LIKE 'P,%' 不会误命中同前缀的更长 ID")
    void commaBoundaryMatters() {
        NodePath node = new NodePath(100L, 0L, "0", NodePath.NODE_TYPE_ADMIN, 100L);
        String prefix = node.selfPrefix();
        assertThat(prefix).isEqualTo("0,100");

        String sibling = "0,1001";     // 另一个节点，ID 恰好以 100 开头
        assertThat(sibling.startsWith(prefix + ",")).isFalse();
        assertThat(sibling).isNotEqualTo(prefix);
        // 而少一个逗号的写法会误命中它
        assertThat(sibling.startsWith(prefix)).isTrue();
    }
}
