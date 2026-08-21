package com.edumatrix.vod.play.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.media.VodMediaClient;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.vod.media.entity.VodVideo;
import com.edumatrix.vod.media.mapper.VodVideoMapper;
import com.edumatrix.vod.play.PlayAuthConst;
import com.edumatrix.vod.play.PlayAuthKeys;
import com.edumatrix.vod.play.entity.VodPlayAuthLog;
import com.edumatrix.vod.play.mapper.VodPlayAuthLogMapper;
import com.edumatrix.vod.play.vo.PlayAuthVO;
import com.edumatrix.vod.progress.ProgressSnapshotReader;

/**
 * 播放凭证发放（03-03 §8.1，接口 28）。
 *
 * <p>校验交给 {@link PlayAuthChainService}（唯一的那道闸）；本类只负责<b>签发与记账</b>：
 * 取 VidAuth → 生成 {@code authToken} 写 Redis → 组装水印 → 写审计。
 */
@Service
public class PlayAuthService {

    private final PlayAuthChainService chain;
    private final VodMediaClient vodClient;
    private final VodVideoMapper videoMapper;
    private final VodPlayAuthLogMapper auditMapper;
    private final ProgressSnapshotReader progressReader;
    private final StringRedisTemplate redis;

    public PlayAuthService(PlayAuthChainService chain, VodMediaClient vodClient,
                           VodVideoMapper videoMapper, VodPlayAuthLogMapper auditMapper,
                           ProgressSnapshotReader progressReader, StringRedisTemplate redis) {
        this.chain = chain;
        this.vodClient = vodClient;
        this.videoMapper = videoMapper;
        this.auditMapper = auditMapper;
        this.progressReader = progressReader;
        this.redis = redis;
    }

    @Transactional(rollbackFor = Exception.class)
    public PlayAuthVO issue(Long lessonId, String clientIp) {
        // ⚠ 走静态门面而不是注入 CurrentContextProvider：测试上下文里有一个 @Primary 替身 bean，
        //   直接注入会拿到它而不是会话那个 —— 表现是「登录了但 nodeId 恒为 null」，
        //   而 TenantHelper 由测试基类统一切换指向（实测踩过一次，五步在第 3 步全挂）
        Long viewerUserId = TenantHelper.getUserId();
        Long viewerNodeId = TenantHelper.getNodeId();
        Integer viewerType = TenantHelper.getUserType();

        // ── 唯一的那道闸（五步）
        PlayAuthChainService.VerifiedPlay play = chain.verify(viewerNodeId, viewerType, lessonId);

        // ── vod_file_id：VideoRef 不带它，同域直接读
        VodVideo video = videoMapper.selectOne(Wrappers.<VodVideo>lambdaQuery()
                .select(VodVideo::getId, VodVideo::getVodFileId, VodVideo::getEncryptType)
                .eq(VodVideo::getId, play.videoId()));
        if (video == null || video.getVodFileId() == null || video.getVodFileId().isBlank()) {
            // 五步都过了却没有云端 ID —— 数据不一致，不是学生的问题，但也不能下发一个空 vid
            throw new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }

        // ── VidAuth：阿里云签发，有它自己的有效期，我们控制不了也读不到
        String playAuth = vodClient.getVideoPlayAuth(video.getVodFileId());

        // ── authToken：我们自己的心跳身份票，TTL 固定 300s（≠ playAuth 的有效期）
        String authToken = UUID.randomUUID().toString().replace("-", "");
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        writeAuthToken(authToken, play, sessionId);

        ProgressSnapshotReader.Snapshot snapshot = progressReader.read(play.studentId(), lessonId);

        writeAudit(play, viewerUserId, viewerType, authToken, clientIp);

        return new PlayAuthVO(video.getVodFileId(), playAuth,
                PlayAuthConst.aliplayerEncryptTypeOf(video.getEncryptType()), sessionId,
                authToken, PlayAuthConst.AUTH_EXPIRE_SECONDS, snapshot.maxPosition(),
                snapshot.watchedDuration(), snapshot.watchStatus());
    }

    private void writeAuthToken(String authToken, PlayAuthChainService.VerifiedPlay play, String sessionId) {
        Map<String, String> value = new HashMap<>();
        if (play.studentId() != null) {
            value.put(PlayAuthKeys.FIELD_STUDENT_ID, String.valueOf(play.studentId()));
        }
        value.put(PlayAuthKeys.FIELD_LESSON_ID, String.valueOf(play.lessonId()));
        value.put(PlayAuthKeys.FIELD_VIDEO_ID, String.valueOf(play.videoId()));
        value.put(PlayAuthKeys.FIELD_SESSION_ID, sessionId);
        String key = PlayAuthKeys.playAuth(authToken);
        redis.opsForHash().putAll(key, value);
        redis.expire(key, PlayAuthConst.AUTH_EXPIRE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * <b>管理端预览同样落审计</b>（03-03 §8.1）：能看到全机构课程的人批量取证，
     * 正是最需要留痕的场景。<b>{@code student_id} 仅 {@code viewer_type=3} 时写</b>。
     */
    private void writeAudit(PlayAuthChainService.VerifiedPlay play, Long viewerUserId,
                            Integer viewerType, String authToken, String clientIp) {
        VodPlayAuthLog row = new VodPlayAuthLog();
        row.setEventType(PlayAuthConst.EVENT_TYPE_PLAY_AUTH);
        row.setViewerUserId(viewerUserId);
        row.setViewerType(viewerType);
        row.setStudentId(play.studentId());
        row.setLessonId(play.lessonId());
        row.setVideoId(play.videoId());
        // 存的是我们自己的 authToken，【不是 playAuth】——后者能直接解密播放，落库等于把它散出去
        row.setAuthToken(authToken);
        row.setExpireTime(LocalDateTime.now().plusSeconds(PlayAuthConst.AUTH_EXPIRE_SECONDS));
        row.setClientIp(clientIp);
        auditMapper.insert(row);
    }

}
