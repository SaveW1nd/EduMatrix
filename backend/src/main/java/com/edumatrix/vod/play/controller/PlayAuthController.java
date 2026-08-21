package com.edumatrix.vod.play.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.response.R;
import com.edumatrix.vod.play.dto.PlayAuthReq;
import com.edumatrix.vod.play.service.PlayAuthService;
import com.edumatrix.vod.play.vo.PlayAuthVO;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 接口 28 · 获取播放凭证（03-03 §8.1）。<b>整条链上唯一的那道闸。</b>
 *
 * <p><b>没有 {@code @SaCheckPermission}，只要求已登录</b>：学生端本就没有菜单权限位，
 * 而「谁有资格播」由 {@link com.edumatrix.vod.play.service.PlayAuthChainService} 的五步回答，
 * 不是由权限位回答。加一个权限位在这里不会让它更安全，只会让学生全被 403。
 */
@RestController
@RequestMapping("/api/v1/vod")
public class PlayAuthController {

    private final PlayAuthService playAuthService;

    public PlayAuthController(PlayAuthService playAuthService) {
        this.playAuthService = playAuthService;
    }

    @PostMapping("/play-auth")
    public R<PlayAuthVO> playAuth(@Valid @RequestBody PlayAuthReq req, HttpServletRequest request) {
        return R.ok(playAuthService.issue(req.getLessonId(), clientIp(request)));
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp : request.getRemoteAddr();
    }
}
