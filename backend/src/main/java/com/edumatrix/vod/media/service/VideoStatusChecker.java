package com.edumatrix.vod.media.service;

import org.springframework.stereotype.Component;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.media.VideoRef;
import com.edumatrix.common.media.VideoRefReader;
import com.edumatrix.common.response.BizException;
import com.edumatrix.vod.media.entity.VodVideo;

/**
 * 媒资「能不能播」的<b>唯一</b>判定入口（04-实施计划.md §B 模块 09 对外产出）。
 *
 * <h2>为什么 {@code 20003} 与 {@code 20015} 的分工在这里、不在五个接口里</h2>
 * <table border="1">
 *   <caption>两个码的边界</caption>
 *   <tr><th>码</th><th>什么时候</th><th>语义</th></tr>
 *   <tr><td>{@code 20003} 视频转码未完成</td><td>{@code status ∈ {0 上传中, 1 转码中}}</td>
 *       <td><b>等一等还有救</b> —— 云端还在跑</td></tr>
 *   <tr><td>{@code 20015} 媒资不存在或状态不允许该操作</td><td>{@code status ∈ {3 转码失败, 9 禁用}}</td>
 *       <td><b>等也没用</b> —— 要么重转要么启用，都得人动手</td></tr>
 * </table>
 * <p>这两个码<b>不属于本模块那五个接口</b>：它们回答的是「要<b>用</b>这个视频」，
 * 而五个接口回答的是「要<b>造 / 改</b>这个视频」。消费方是<b>模块 12</b>（播放凭证与解密密钥）
 * 与<b>模块 08 §1.6</b>（课程上架时校验全部视频课时）。
 *
 * <p><b>媒资不存在时也抛 {@code 20015}</b>：调用方（模块 12）拿到的 {@code videoId}
 * 来自课时行、不是用户输入的路径参数，所以这里不走 F-42 的 404 那条 ——
 * 那条治的是「按 id 探测存在性」，而这里的 id 根本不由调用方选。
 *
 * <p><b>放在 {@code vod} 域而不是 {@code common}</b>：消费方模块 12 是 {@code vod/play}，
 * 同域，不需要跨领域 SPI；模块 08 那一处经 {@code VideoRefReader} 拿状态后自己判
 * （它早于本模块存在，且它要的是 {@code 20008} 不是这两个码）。
 */
@Component
public class VideoStatusChecker {

    private final VideoRefReader videoRefReader;

    public VideoStatusChecker(VideoRefReader videoRefReader) {
        this.videoRefReader = videoRefReader;
    }

    /**
     * 断言该媒资可播；不可播直接抛。
     *
     * @throws BizException {@code 20003}（转码未完成）或 {@code 20015}（不存在 / 失败 / 禁用）
     */
    public VideoRef assertPlayable(Long videoId) {
        VideoRef ref = videoRefReader.read(videoId);
        if (ref == null || ref.status() == null) {
            throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }
        int status = ref.status();
        if (status == VodVideo.STATUS_UPLOADING || status == VodVideo.STATUS_TRANSCODING) {
            throw new BizException(ErrorCode.VIDEO_TRANSCODE_NOT_FINISHED);
        }
        if (status != VodVideo.STATUS_NORMAL) {
            // 3 转码失败 / 9 禁用，以及任何将来新增的非 2 取值 ——
            // 【白名单而不是黑名单】：新增一个状态时默认不可播，而不是默认可播
            throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }
        return ref;
    }
}
