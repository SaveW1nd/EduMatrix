package com.edumatrix.vod.media.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.response.R;
import com.edumatrix.vod.media.dto.UploadTokenReq;
import com.edumatrix.vod.media.dto.VideoPageQuery;
import com.edumatrix.vod.media.dto.VideoStatusReq;
import com.edumatrix.vod.media.service.VodVideoService;
import com.edumatrix.vod.media.vo.UploadTokenVO;
import com.edumatrix.vod.media.vo.VideoListVO;
import com.edumatrix.vod.media.vo.VideoStatusVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * VOD 媒资管理（03-03 §7，接口 25 / 26 / 27 / 33 / 34）。
 *
 * <p><b>本模块不新增任何免登录端点</b>（契约 §2.8）：转码事件走 XXL-Job 拉取轻量消息队列，
 * 去掉原回调端点之后，全部免登录接口里<b>再没有一个能写库</b>。
 * 这五条全部经 Sa-Token 鉴权 + {@code @SaCheckPermission}。
 *
 * <p>权限标识来自契约 §10 附表 A（{@code vod:video:*} 五个，绑 org_admin 与 teacher）。
 */
@RestController
@RequestMapping("/api/v1/vod/videos")
public class VodVideoController {

    private final VodVideoService videoService;

    public VodVideoController(VodVideoService videoService) {
        this.videoService = videoService;
    }

    /**
     * 接口 25 §7.1 获取视频上传凭证（含续签 / 重传）。
     *
     * <p>用 {@code vod:video:add}：新建与重传都是「往媒资库里放东西」。
     *
     * <h2>⚠ 需方 2026-08-21 定案二：<b>仅 {@code org_admin}</b>，教师不再能上传</h2>
     * <p>原话「我最初的想法是所有的资产归超级管理员所有，别人无权上传」。
     *
     * <p><b>这里一个字都不改</b> —— 权限的真相在初始化数据里
     *（{@code sys_role_menu} → {@code sys_menu.perms}），代码里不写第二份。
     * 收窄靠迁移 {@code V202608210000__revoke_teacher_video_upload_perm.sql}
     * 撤销 {@code teacher → vod:video:add} 那一行绑定，与 F-72 逐字同源。
     * <b>加一个角色门会是全库唯一一处不同构的判定</b>，多一个会配错的地方。
     *
     * <p>判据是 {@code VodVideoIT#uploadTokenIsOrgAdminOnly} 的<b>两侧</b>：
     * 教师 403、管理员 200。只断言一侧不行 —— 把权限写死拒绝也能让「教师 403」全绿。
     */
    @PostMapping("/upload-token")
    @SaCheckPermission("vod:video:add")
    @OperLog(module = "媒资", action = "获取上传凭证")
    public R<UploadTokenVO> uploadToken(@RequestBody @Valid UploadTokenReq req) {
        return R.ok(videoService.issueUploadToken(req));
    }

    /** 接口 26 §7.3 媒资分页列表。响应<b>不含</b> {@code hlsUrl}。 */
    @GetMapping
    @SaCheckPermission("vod:video:list")
    public R<PageResult<VideoListVO>> page(VideoPageQuery query) {
        return R.ok(videoService.page(query));
    }

    /** 接口 27 §7.4 删除媒资。被未删除课时引用时 {@code 20016}。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("vod:video:remove")
    @OperLog(module = "媒资", action = "删除媒资")
    public R<Void> delete(@PathVariable Long id) {
        videoService.delete(id);
        return R.ok();
    }

    /** 接口 33 §7.5 重新发起转码。仅 {@code status=3} 可调，否则 {@code 20015}。 */
    @PostMapping("/{id}/retranscode")
    @SaCheckPermission("vod:video:retranscode")
    @OperLog(module = "媒资", action = "重新发起转码")
    public R<VideoStatusVO> retranscode(@PathVariable Long id) {
        return R.ok(videoService.retranscode(id));
    }

    /** 接口 34 §7.6 媒资禁用/启用。仅 {@code 2 ↔ 9}，其余目标值 400、其余当前状态 {@code 20015}。 */
    @PutMapping("/{id}/status")
    @SaCheckPermission("vod:video:status")
    @OperLog(module = "媒资", action = "媒资禁用启用")
    public R<VideoStatusVO> changeStatus(@PathVariable Long id,
                                         @RequestBody @Valid VideoStatusReq req) {
        return R.ok(videoService.changeStatus(id, req));
    }
}
