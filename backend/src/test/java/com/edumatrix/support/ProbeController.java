package com.edumatrix.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.response.R;

/**
 * 探针 Controller：<b>只存在于 {@code src/test}</b>。
 *
 * <p><b>为什么是测试专用而不是真接口</b>：模块 01 的「涉及接口」是<b>无</b>
 * （04-实施计划.md 模块 01）。而完成判据第 2 条要求「任一接口响应满足统一响应体 /
 * ID 字符串化 / 东八区时间；响应头带 {@code X-Trace-Id}」—— 两者只能这样同时满足：
 * 用一个测试专用端点去验公共层的行为，<b>生产代码里不新增任何路由</b>。
 * 自造一条 {@code /api/v1/...} 就是发明接口路径，那是纪律明令禁止的。
 */
@RestController
public class ProbeController {

    /** 与业务无关的探针路径，刻意不放在 {@code /api/v1} 下。 */
    public static final String PATH_OBJECT = "/__probe/object";
    public static final String PATH_PAGE = "/__probe/page";

    @GetMapping(PATH_OBJECT)
    public R<ProbeVo> object() {
        return R.ok(new ProbeVo(
                1953827104412590081L,
                null,
                "示例",
                LocalDateTime.of(2026, 8, 12, 10, 30, 0),
                LocalDate.of(2026, 8, 12),
                600));
    }

    @GetMapping(PATH_PAGE)
    public R<PageResult<ProbeVo>> page() {
        return R.ok(PageResult.of(138L, List.of(new ProbeVo(
                1953827104412590082L,
                1953827104412590001L,
                "分页项",
                LocalDateTime.of(2026, 8, 12, 10, 30, 0),
                LocalDate.of(2026, 8, 12),
                0))));
    }

    /**
     * 探针响应体，覆盖 00-通用约定 §5 / §6 涉及的四类字段。
     *
     * @param id         包装 {@code Long} → 序列化为字符串
     * @param parentId   可空 ID，同样是字符串；为 null 时输出 null
     * @param name       普通字符串
     * @param createTime {@code yyyy-MM-dd HH:mm:ss}
     * @param statDate   {@code yyyy-MM-dd}
     * @param duration   时长，一律以<b>秒</b>为整数传输
     */
    public record ProbeVo(Long id, Long parentId, String name,
                          LocalDateTime createTime, LocalDate statDate, int duration) {
    }
}
