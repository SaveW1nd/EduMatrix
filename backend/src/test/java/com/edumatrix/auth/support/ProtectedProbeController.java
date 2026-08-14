package com.edumatrix.auth.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.response.R;

/**
 * 一条<b>受保护的</b>探针接口，<b>只存在于 {@code src/test}</b>。
 *
 * <h2>为什么需要它</h2>
 * <p>模块 02 有两条判据要验「<b>除认证接口外的其他接口</b>会怎样」：
 * <ul>
 *   <li>判据 2：{@code pwd_reset_flag = 1} 时访问其他接口 → 403；
 *   <li>判据 4：所在分支被停用后，已持有的 accessToken 下一次请求 → {@code 10017}。
 * </ul>
 * 而此刻全系统只有 {@code /api/v1/auth/**} 六条路由，其中三条免登录、三条又都在
 * 强制改密的放行清单里 —— <b>没有一条能用来验「其他接口」</b>。
 * 第一个业务接口要等模块 03。
 *
 * <p>所以造一条测试专用端点，<b>生产代码里不新增任何路由</b>（模块 01 的
 * {@code support/ProbeController} 已立此先例：自造一条 {@code /api/v1/...}
 * 就是发明接口路径，那是纪律明令禁止的）。路径同样刻意<b>不放在 {@code /api/v1} 下</b>，
 * 但仍会被 Sa-Token 拦截器覆盖 —— 它的 {@code addPathPatterns} 是 {@code /**}，
 * 只排除白名单四条与两条基础设施路径。
 */
@RestController
public class ProtectedProbeController {

    /** 需要登录、且不在强制改密放行清单里的路径。 */
    public static final String PATH = "/__probe/protected";

    @GetMapping(PATH)
    public R<String> protectedResource() {
        return R.ok("ok");
    }
}
