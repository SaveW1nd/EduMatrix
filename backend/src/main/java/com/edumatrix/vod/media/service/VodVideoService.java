package com.edumatrix.vod.media.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.account.UserNameReader;
import com.edumatrix.common.course.LessonVideoRefCounter;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.media.VodMediaClient;
import com.edumatrix.common.media.VodUploadCredential;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.subtree.NodeNameReader;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.vod.media.dto.UploadTokenReq;
import com.edumatrix.vod.media.dto.VideoPageQuery;
import com.edumatrix.vod.media.dto.VideoStatusReq;
import com.edumatrix.vod.media.entity.VodVideo;
import com.edumatrix.vod.media.mapper.VodVideoMapper;
import com.edumatrix.vod.media.vo.UploadTokenVO;
import com.edumatrix.vod.media.vo.VideoListVO;
import com.edumatrix.vod.media.vo.VideoStatusVO;

/**
 * 媒资管理五个接口（03-03 §7.1 / §7.3 / §7.4 / §7.5 / §7.6）。
 *
 * <h2>三个错误码的分工（本类只用到后两个）</h2>
 * <table border="1">
 *   <caption>20003 / 20015 / 20016</caption>
 *   <tr><th>码</th><th>归谁</th></tr>
 *   <tr><td>{@code 20003}</td><td><b>不属于本类</b> —— 归 {@link VideoStatusChecker}，
 *       它回答「要<b>用</b>这个视频」；本类五个接口回答「要<b>造 / 改</b>这个视频」</td></tr>
 *   <tr><td>{@code 20015}</td><td><b>当前状态不满足该操作的前置条件</b>，
 *       外加 §7.1 请求体里那个 {@code videoId} 查不到（param-addressed，F-42 边界）</td></tr>
 *   <tr><td>{@code 20016}</td><td><b>只有 §7.4 删除</b>一处</td></tr>
 * </table>
 * <p>路径上的 404 / 403 由 {@link VodVideoAccessGuard} 统一判（F-49）。
 *
 * <h2>状态跃迁一律用 CAS，不用「先读后写」</h2>
 * <p>每一处改 {@code status} 的 UPDATE 都带 {@code AND status = 期望值}，
 * 命中 0 行即抛 {@code 20015}。「先 select 判状态、再 update」在并发下会让两个请求
 * 都通过判断然后都写 —— 而这类竞态<b>不会报错</b>，只会让状态机悄悄走出一条不存在的边。
 */
@Service
public class VodVideoService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 凭证有效期 3600s（03-03 §7.1 流程说明第 4 条）。 */
    private static final long UPLOAD_AUTH_TTL_SECONDS = 3600L;

    /** {@code grantType}：1 自有 2 被授权（§7.3 逐行标识）。 */
    private static final int GRANT_TYPE_OWNED = 1;
    private static final int GRANT_TYPE_GRANTED = 2;

    private final VodVideoMapper videoMapper;
    private final VodVideoAccessGuard guard;
    private final VodMediaClient vodClient;
    private final LessonVideoRefCounter lessonRefCounter;
    private final ResourceGrantReader grantReader;
    private final NodeNameReader nodeNameReader;
    private final UserNameReader userNameReader;

    public VodVideoService(VodVideoMapper videoMapper,
                           VodVideoAccessGuard guard,
                           VodMediaClient vodClient,
                           LessonVideoRefCounter lessonRefCounter,
                           ResourceGrantReader grantReader,
                           NodeNameReader nodeNameReader,
                           UserNameReader userNameReader) {
        this.videoMapper = videoMapper;
        this.guard = guard;
        this.vodClient = vodClient;
        this.lessonRefCounter = lessonRefCounter;
        this.grantReader = grantReader;
        this.nodeNameReader = nodeNameReader;
        this.userNameReader = userNameReader;
    }

    // =====================================================================
    // 接口 25 §7.1 获取视频上传凭证
    // =====================================================================

    /**
     * 判定顺序：参数 400 → 新建 or 续签分支。
     *
     * <h2>新建：<b>先调云、再落库</b></h2>
     * <p>DDL 对 {@code vod_file_id} 的注释逐字「阿里云路径下发上传凭证时即写入……
     * NULL 仅出现在腾讯路径的预创建态」。先落库会造出一条阿里云路径下<b>不该存在</b>的 NULL 行；
     * 云调失败则一行都不落，云端多一个空 VideoId（30 天自清），本地无脏行。
     *
     * <h2>重传：{@code 3 → 1}，<b>不经过 0</b>（F-51）</h2>
     * <p>§7.1 那句「重传成功走事件消费重新流转 {@code 0→1→2/3}」与 §9 状态机速查
     * （03-03 第 2182 行）逐字冲突：「{@code 3 →（接口 33 重新发起转码 /
     * <b>接口 25 获取视频上传凭证 重传源文件</b>）→ <b>1</b>》」。取状态机速查 ——
     * 因为 {@code FileUploadComplete} 的映射写死是 {@code 0→1}，若置 0
     * 则重传后那条事件到达时 CAS 命中 0 行，媒资<b>永远停在 3</b>。
     *
     * <p><b>⚠ 由此产生一个死角，已登记 F-65，本轮按分册实现不开口子</b>：
     * 置 1 之后若用户放弃上传，§7.1 要 {@code status ∈ {0,3}}、§7.5 要 {@code status = 3}，
     * <b>两个接口都进不去</b>，媒资永久停在「转码中」，且 PRD F2-3 规则 3 的
     * 「转码失败」待办计数已经 -1、红色标记与重试入口一并消失。
     */
    @Transactional(rollbackFor = Exception.class)
    public UploadTokenVO issueUploadToken(UploadTokenReq req) {
        if (req.getVideoId() == null) {
            return createNew(req);
        }
        return refreshExisting(req);
    }

    private UploadTokenVO createNew(UploadTokenReq req) {
        Long ownerNodeId = guard.myNodeId();
        VodUploadCredential credential =
                vodClient.createUploadVideo(req.getVideoName().trim(), req.getFileName().trim(),
                        req.getFileSize());

        VodVideo video = new VodVideo();
        video.setOwnerNodeId(ownerNodeId);
        video.setProvider(VodVideo.PROVIDER_ALIYUN);
        // 【这里写的是【占位值】，不是判断 —— F-114 第二半】
        // 上传这一刻【我们并不知道模板组会产出什么】：加密与否要等转码完成、GetPlayInfo 回来才知道。
        // 真值由 VodEventConsumeService 在 TranscodeComplete 时按【实际挑中那一路】回填
        // （VodPlayStream.resolvedEncryptType()）。
        //
        // ⚠ 原注释写着「写 0 的前提是需方侧模板已配成不加密输出」——【那个约束在新设计下不存在了】，
        //   留着就是在描述一个不存在的前提。现在模板配成什么都不影响这一行。
        //
        // 仍然显式写而不靠 DDL 默认值：默认值是 1（HLS 标准加密），而 status=0/1 期间
        // 这一列还没有真值，落一个「标准加密」比落一个中性的 0 更容易被误读成事实。
        video.setEncryptType(VodVideo.ENCRYPT_NONE);
        video.setVodFileId(credential.cloudVideoId());
        video.setVideoName(req.getVideoName().trim());
        video.setStatus(VodVideo.STATUS_UPLOADING);
        video.setUploadUserId(TenantHelper.getUserId());
        video.setDuration(0);
        video.setSizeBytes(0L);
        videoMapper.insert(video);

        return toTokenVO(video.getId(), credential);
    }

    private UploadTokenVO refreshExisting(UploadTokenReq req) {
        // 【请求体里的 videoId】查不到给业务码而不是 404 —— F-42 的 PARAM 一档：
        // 用户主动选了这个对象，选错了要明确提示，返 404 会让他以为端点写错了
        VodVideo video = videoMapper.selectById(req.getVideoId());
        if (video == null) {
            throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }
        // §7.1 权限栏只写了角色，没写 owner 要求（F-50）。按 §7.4/§7.5/§7.6 同口径要求 owner：
        // 续签/重传会改别人的媒资，是写操作；不要求 owner 等于让被授权方替 owner 重传源文件
        if (!guard.isOwned(video, guard.myNodeId())) {
            throw BizException.forbidden();
        }
        int status = video.getStatus() == null ? -1 : video.getStatus();
        if (status != VodVideo.STATUS_UPLOADING && status != VodVideo.STATUS_FAILED) {
            throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }

        VodUploadCredential credential = vodClient.refreshUploadVideo(video.getVodFileId());

        if (status == VodVideo.STATUS_FAILED) {
            // 3 → 1（F-51）。remark 一并清掉：留着上次的失败原因而状态是「转码中」，
            // 管理端列表会同时显示「转码中」与一条失败文案
            int changed = videoMapper.update(null, new LambdaUpdateWrapper<VodVideo>()
                    .eq(VodVideo::getId, video.getId())
                    .eq(VodVideo::getStatus, VodVideo.STATUS_FAILED)
                    .set(VodVideo::getStatus, VodVideo.STATUS_TRANSCODING)
                    .set(VodVideo::getRemark, null));
            if (changed == 0) {
                throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
            }
        }
        return toTokenVO(video.getId(), credential);
    }

    private static UploadTokenVO toTokenVO(Long videoId, VodUploadCredential credential) {
        UploadTokenVO.Credential vo = new UploadTokenVO.Credential();
        vo.setUploadAuth(credential.uploadAuth());
        vo.setUploadAddress(credential.uploadAddress());
        vo.setCloudVideoId(credential.cloudVideoId());
        vo.setExpireTime(LocalDateTime.now().plusSeconds(UPLOAD_AUTH_TTL_SECONDS).format(TIME));

        UploadTokenVO token = new UploadTokenVO();
        token.setVideoId(videoId);
        token.setProvider(VodVideo.PROVIDER_ALIYUN);
        token.setCredential(vo);
        return token;
    }

    // =====================================================================
    // 接口 26 §7.3 媒资分页列表
    // =====================================================================

    /**
     * 可见集 = {@code owner_node_id = 我的节点} ∪ 已显式授权给我的节点且在有效期内（§0.2）。
     * <b>不回溯祖先链、无继承</b>。
     */
    public PageResult<VideoListVO> page(VideoPageQuery query) {
        Long myNodeId = guard.myNodeId();
        List<Long> grantedIds = grantReader.grantedResourceIds(ResourceType.VIDEO, myNodeId);

        // 可见性谓词抽在 VodVideoVisibilityPredicate —— 模块 11 的接口 37 用同一条，
        // 差别只在传进去的 ID 清单口径（canUse → canRegrant）。本处不带 source 筛选，
        // 传 null 取并集（03-03 §7.3 参数表没有该筛选项）
        LambdaQueryWrapper<VodVideo> wrapper = VodVideoVisibilityPredicate.apply(
                new LambdaQueryWrapper<>(), myNodeId, grantedIds, null);
        if (query.getVideoName() != null && !query.getVideoName().isBlank()) {
            wrapper.like(VodVideo::getVideoName, query.getVideoName().trim());
        }
        if (query.getStatus() != null) {
            wrapper.eq(VodVideo::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(VodVideo::getCreateTime).orderByDesc(VodVideo::getId);

        IPage<VodVideo> page = videoMapper.selectPage(
                new Page<>(PageResult.normalizePageNum(query.getPageNum()),
                        PageResult.normalizePageSize(query.getPageSize())), wrapper);

        List<VodVideo> rows = page.getRecords();
        Map<Long, Integer> refCounts = lessonRefCounter.countByVideos(
                rows.stream().map(VodVideo::getId).toList());
        Map<Long, String> nodeNames = nodeNameReader.nodeNames(
                rows.stream().map(VodVideo::getOwnerNodeId).filter(Objects::nonNull).distinct().toList());
        Map<Long, String> userNames = userNameReader.realNames(
                rows.stream().map(VodVideo::getUploadUserId).filter(Objects::nonNull).distinct().toList());

        List<VideoListVO> list = new ArrayList<>(rows.size());
        for (VodVideo video : rows) {
            VideoListVO vo = new VideoListVO();
            vo.setId(video.getId());
            vo.setProvider(video.getProvider());
            vo.setVodFileId(video.getVodFileId());
            vo.setVideoName(video.getVideoName());
            vo.setOwnerNodeId(video.getOwnerNodeId());
            vo.setOwnerNodeName(nodeNames.get(video.getOwnerNodeId()));
            vo.setGrantType(guard.isOwned(video, myNodeId) ? GRANT_TYPE_OWNED : GRANT_TYPE_GRANTED);
            vo.setDuration(video.getDuration());
            vo.setCoverUrl(video.getCoverUrl());
            vo.setSizeBytes(video.getSizeBytes());
            vo.setStatus(video.getStatus());
            vo.setRefLessonCount(refCounts.getOrDefault(video.getId(), 0));
            vo.setUploadUserId(video.getUploadUserId());
            vo.setUploadUserName(userNames.get(video.getUploadUserId()));
            vo.setCreateTime(video.getCreateTime());
            vo.setUpdateTime(video.getUpdateTime());
            list.add(vo);
        }
        return PageResult.of(page.getTotal(), list);
    }

    // =====================================================================
    // 接口 27 §7.4 删除媒资
    // =====================================================================

    /**
     * 判定顺序：404（不存在/不可见）→ 403（非 owner）→ {@code 20016}（被引用）→ 逻辑删除。
     *
     * <p><b>云端源文件不随删清理</b>（§7.4 说明：平台级异步清理，默认保留 30 天，避免误删不可恢复）。
     * <p><b>{@code 20015} 在本接口没有可达场景</b>：删除没有任何状态前置条件，
     * 而「不存在」已按 F-49 收到 404。§7.4 的错误码栏应只剩 {@code 20016}（文档订正在提交 6）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long videoId) {
        VodVideo video = guard.loadOwnedByPath(videoId);
        if (lessonRefCounter.countByVideo(video.getId()) > 0) {
            throw new BizException(ErrorCode.VIDEO_IN_USE);
        }
        videoMapper.deleteById(video.getId());
    }

    // =====================================================================
    // 接口 33 §7.5 重新发起转码
    // =====================================================================

    /**
     * 判定顺序：404 → 403 → {@code status ≠ 3} 则 {@code 20015} → 事务内 CAS 3→1 + 调云。
     *
     * <h2>为什么把云调用放进事务</h2>
     * <p>换来的是<b>没有中间态</b>：云调抛异常则整体回滚，{@code status} 退回 3。
     * 反过来「先调云再改状态」会在落库失败时把媒资<b>永久卡在 3 而云端已在转</b>。
     * 代价是持事务期间有网络 I/O —— 这是个低频接口，可接受，且 SDK 有 3s/5s 硬超时。
     *
     * <p><b>残留一种失序</b>：云调<b>实际成功但响应丢失</b> → 事务回滚、{@code status=3}，
     * 而 {@code TranscodeComplete} 随后到达。为此消费侧的成功分支 CAS 前置状态集<b>包含 3</b>
     * （见消费链路那个提交）。两层一起才收得住，登记 <b>F-60</b>。
     */
    @Transactional(rollbackFor = Exception.class)
    public VideoStatusVO retranscode(Long videoId) {
        VodVideo video = guard.loadOwnedByPath(videoId);
        if (video.getStatus() == null || video.getStatus() != VodVideo.STATUS_FAILED) {
            throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }
        int changed = videoMapper.update(null, new LambdaUpdateWrapper<VodVideo>()
                .eq(VodVideo::getId, video.getId())
                .eq(VodVideo::getStatus, VodVideo.STATUS_FAILED)
                .set(VodVideo::getStatus, VodVideo.STATUS_TRANSCODING)
                .set(VodVideo::getRemark, null));
        if (changed == 0) {
            throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }
        vodClient.submitTranscodeJobs(video.getVodFileId());
        return VideoStatusVO.of(video.getId(), VodVideo.STATUS_TRANSCODING);
    }

    // =====================================================================
    // 接口 34 §7.6 媒资禁用/启用
    // =====================================================================

    /**
     * 判定顺序：{@code targetStatus ∉ {2,9}} → 400（在查库<b>之前</b>，由 DTO 的
     * {@code @AssertTrue} 承担）→ 404 → 403 → 不构成 {@code 2↔9} 合法切换 → {@code 20015}
     * → CAS。
     *
     * <p>{@code 0/1/3} 一律 {@code 20015}：§7.6 逐字「转码状态一律由 §7.2 的事件消费驱动」——
     * 人工接口不得插手转码态，否则「谁在推进状态机」就有了两个答案。
     */
    @Transactional(rollbackFor = Exception.class)
    public VideoStatusVO changeStatus(Long videoId, VideoStatusReq req) {
        VodVideo video = guard.loadOwnedByPath(videoId);
        int target = req.getTargetStatus();
        int expected = target == VodVideo.STATUS_DISABLED
                ? VodVideo.STATUS_NORMAL       // 置 9 禁用：仅 2 可禁用
                : VodVideo.STATUS_DISABLED;    // 置 2 启用：仅 9 可恢复
        if (video.getStatus() == null || video.getStatus() != expected) {
            throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }
        LambdaUpdateWrapper<VodVideo> update = new LambdaUpdateWrapper<VodVideo>()
                .eq(VodVideo::getId, video.getId())
                .eq(VodVideo::getStatus, expected)
                .set(VodVideo::getStatus, target);
        if (req.getRemark() != null) {
            update.set(VodVideo::getRemark, req.getRemark().trim());
        }
        if (videoMapper.update(null, update) == 0) {
            throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }
        VideoStatusVO vo = VideoStatusVO.of(video.getId(), target);
        // 供前端在禁用确认弹窗提示影响面（§7.6 响应字段说明）
        vo.setRefLessonCount(lessonRefCounter.countByVideo(video.getId()));
        return vo;
    }
}
