package com.edumatrix.system.file.service;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.file.FileConstants;
import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.common.response.BizException;

/**
 * 上传频次闸（D-5 定案）：同一用户 60 秒内最多 20 次，超限 HTTP 429 + {@code Retry-After}。
 *
 * <h2>为什么需要它 —— 全库此前<b>一条频次或配额规定都没有</b></h2>
 * <p>核实过：PRD / 契约 / {@code 00-通用约定} 三份文档里「配额 / 存储空间 / 上传频次」
 * 四个关键词<b>零命中</b>；{@code 00-通用约定} §8 限流表只有 login / captcha / 心跳三条。
 * 而 03-01 §7.1 的允许角色<b>明确包含 {@code student}</b>，
 * 且三个文件接口按契约 §3.1 边界 0 不加 {@code @SaCheckPermission}。
 * 也就是说：<b>任何一个学生账号可以循环上传，而系统里没有任何一条规则说这不行。</b>
 *
 * <h2>按 {@code userId} 而不是 IP</h2>
 * <p>{@code LoginRateLimiter} 按 IP，是因为登录与验证码是<b>未登录</b>接口、只有 IP 可用。
 * 上传接口一定有会话，按 userId 更准：校园网整栋楼共用一个出口 IP，
 * 按 IP 限流会让一个人刷爆导致全班交不了作业。
 *
 * <h2>这道闸挡不住多少 —— 必须说清</h2>
 * <p>20 次/60 秒 × 100MB = <b>2GB/分钟</b>。真正把它压下来的是
 * {@code FileBizType} 的<b>按 bizType 上限</b>：学生端唯一能传的业务附件
 * {@code answer} 取 10MB（复用 03-01 §7.1 已有的图片档，见 F-36），
 * 于是学生路径上是 200MB/分钟。<b>存储配额本期不做（D-5 定案）</b>，
 * 缺口靠这两道闸收窄，不假装它已经解决。
 *
 * <h2>响应形态照抄 §8 的既有约定，不发明新的</h2>
 * <p>{@code 00-通用约定} §7.6 逐字：「HTTP 429（触发限流）：读取响应头
 * {@code Retry-After}（秒）后再重试」。与 {@code LoginRateLimiter#checkRate}
 * 是同一段逻辑的第二处实例 —— 两处都只有五行、无状态、无分支差异，
 * 合并要跨 {@code auth} / {@code system} 两个域（检查③ 禁止直接 import），
 * 下沉到 {@code common} 又会让公共层开始持有限流策略。<b>两边注释互指。</b>
 */
@Service
public class FileUploadRateLimiter {

    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private final StringRedisTemplate redisTemplate;

    public FileUploadRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 超限抛 {@code 429}。计数在<b>类型与大小校验之前</b>做 —— 否则刷子仍然能让服务端整读文件。 */
    public void check(Long userId) {
        if (userId == null) {
            return;
        }
        String key = RedisKeys.rateLimitFileUpload(userId);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, FileConstants.UPLOAD_RATE_WINDOW);
        }
        if (count != null && count > FileConstants.UPLOAD_RATE_LIMIT) {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            setRetryAfter(ttl == null || ttl < 0 ? FileConstants.UPLOAD_RATE_WINDOW.toSeconds() : ttl);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    private static void setRetryAfter(long seconds) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getResponse() != null) {
            attrs.getResponse().setHeader(HEADER_RETRY_AFTER, String.valueOf(seconds));
        }
    }
}
