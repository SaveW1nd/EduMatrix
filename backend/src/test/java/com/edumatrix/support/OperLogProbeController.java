package com.edumatrix.support;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.R;

/**
 * {@code @OperLog} 切面的探针 Controller，<b>只存在于 {@code src/test}</b>。
 *
 * <p>与 {@link ProbeController} 同一条纪律：<b>生产代码里不新增任何路由</b>
 * （接口总数必须仍是 161），路径刻意不放在 {@code /api/v1} 下。
 *
 * <p><b>为什么不直接拿真业务接口来验切面</b>：真接口的成功路径需要一整棵组织树与
 * 一批前置数据，而这里要验的是切面本身的四件事（{@code params} 脱敏 / {@code cost_ms} /
 * {@code status} + {@code error_msg} / {@code saveParams=false}）。
 * 用真接口验，任何一次业务前置数据的变化都会让这组用例莫名其妙地红，
 * 最后没人相信它 —— 而它守的恰恰是 F-25 那一整块。
 */
@RestController
public class OperLogProbeController {

    public static final String PATH_OK = "/__probe/operlog/ok";
    public static final String PATH_FAIL = "/__probe/operlog/fail";
    public static final String PATH_NO_PARAMS = "/__probe/operlog/no-params";

    public static final String MODULE = "探针模块";
    public static final String ACTION_OK = "探针成功";
    public static final String ACTION_FAIL = "探针失败";
    public static final String ACTION_NO_PARAMS = "探针不记参数";

    /** 成功路径：{@code status=0}，{@code params} 里的口令与手机号必须已脱敏。 */
    @PostMapping(PATH_OK)
    @OperLog(module = MODULE, action = ACTION_OK)
    public R<String> ok(@RequestBody ProbeReq req) {
        return R.ok(req.realName());
    }

    /**
     * 失败路径：抛 {@link BizException}。
     *
     * <p>切面必须记 {@code status=1} 且 {@code error_msg} 带业务码，
     * <b>并把异常原样重抛</b>（不得吞掉、不得改变业务结果）。
     */
    @PostMapping(PATH_FAIL)
    @OperLog(module = MODULE, action = ACTION_FAIL)
    public R<String> fail(@RequestBody ProbeReq req) {
        throw BizException.of(ErrorCode.FILE_TYPE_OR_SIZE_INVALID);
    }

    /** {@code saveParams = false}：整段 {@code params} 不落库（§3.6 重置密码在用这一档）。 */
    @PostMapping(PATH_NO_PARAMS)
    @OperLog(module = MODULE, action = ACTION_NO_PARAMS, saveParams = false)
    public R<String> noParams(@RequestBody ProbeReq req) {
        return R.ok(req.realName());
    }

    /**
     * 覆盖三类字段：普通值、口令（整值替换）、手机号（掩码）。
     *
     * @param realName      普通值，必须一字不改地留在 {@code params} 里
     * @param initPassword  口令，必须整值替换
     * @param guardianPhone K12 敏感个人信息，必须掩码（契约 §7.2）
     */
    public record ProbeReq(String realName, String initPassword, String guardianPhone) {
    }
}
