package com.edumatrix.common.grant;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.grant.mapper.ResourceGrantMapper;
import com.edumatrix.org.node.mapper.NodeGrantScopeMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D7 的<b>静态</b>那一半：全库不得再出现 {@code valid_end > NOW()}。
 *
 * <h2>为什么行为测试之外还要这一条</h2>
 * <p>{@code GrantValidityBoundaryIT} 验的是「到期那一秒两条路径结论相同」，
 * 但它只覆盖<b>它点到的那两条查询</b>。有效期谓词将来会被抄到第三处、第四处，
 * 而抄错一个不等号<b>不会报错</b> —— 只在到期那一秒差一行。
 *
 * <p>本条按<b>文本</b>钉住：只要有人在这两个 Mapper 里写出 {@code valid_end > NOW()}，
 * 立刻红。它跑在单元测试阶段，不需要数据库，因而永远不会因环境问题被跳过。
 *
 * <p><b>覆盖面照实说</b>：只扫这两个 Mapper。它们是<b>目前</b>全部直接写有效期谓词的地方
 *（模块 11 的写侧一律走 {@code ResourceGrantMapper.VALID_NOW}）。
 * 新增第三个直写有效期的 Mapper 时，要把它加进下面这个清单 —— 加不加靠人，
 * 但只要加了，此后就由脚本守着。
 */
class ValidityPredicateIsSingleSourcedTest {

    private static final List<Class<?>> MAPPERS_WRITING_VALIDITY_PREDICATE = List.of(
            ResourceGrantMapper.class,
            NodeGrantScopeMapper.class,
            // 模块 11 新增的四个（按类注释的要求登记进来）：
            com.edumatrix.org.grant.mapper.GrantHealthMapper.class,
            com.edumatrix.org.grant.mapper.OutOfScopeGrantMapper.class,
            com.edumatrix.org.grant.mapper.TransferPrecheckMapper.class,
            com.edumatrix.org.grant.mapper.GrantValidityMapper.class);

    @Test
    @DisplayName("valid_end 的上界一律 >= NOW()；出现 > NOW() 即为 D7 那个分叉重现")
    void noStrictGreaterThanOnValidEnd() {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Class<?> mapper : MAPPERS_WRITING_VALIDITY_PREDICATE) {
            for (String sql : selectStatementsOf(mapper)) {
                scanned++;
                if (sql.contains("valid_end > NOW()")) {
                    offenders.add(mapper.getSimpleName() + "：" + sql);
                }
            }
        }
        assertThat(scanned)
                .as("一条 @Select 都没扫到 —— 本检查正在空转，先确认 Mapper 是不是改用 XML 了")
                .isGreaterThan(3);
        assertThat(offenders)
                .as("有效期上界出现了第二种写法。两者只在到期那一秒结论相反，"
                        + "表现是「一边说这条授权还有效、另一边说已失效」，而两边都返回 200。"
                        + "唯一口径见 02-数据库设计 §3.3.2 与 org_resource_grant 的 DDL 列注释")
                .isEmpty();
    }

    @Test
    @DisplayName("VALID_NOW 这个共用常量本身没被改走样")
    void sharedPredicateStillSaysGreaterOrEqual() {
        assertThat(ResourceGrantMapper.VALID_NOW)
                .contains("deleted_at = 0")
                .contains("NOW() >= valid_start")
                .contains("valid_end >= NOW()");
    }

    private static List<String> selectStatementsOf(Class<?> mapper) {
        List<String> statements = new ArrayList<>();
        for (Method method : mapper.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select != null) {
                statements.addAll(List.of(select.value()));
            }
        }
        return statements;
    }
}
