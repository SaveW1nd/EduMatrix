package com.edumatrix.common.file;

import java.time.Duration;

/**
 * 文件相关的硬约定常量。<b>每一项都能指到一句分册原文</b>，不要在这里放"我觉得合理"的数字。
 */
public final class FileConstants {

    private FileConstants() {
    }

    /**
     * 签名地址有效期 <b>30 分钟</b>。
     *
     * <p>三处同源：{@code 00-通用约定} §7.4「有效期 ≤30 分钟」、03-01 §7.3「有效期 30 分钟」、
     * 03-05 §4.8 {@code downloadUrl}「有效期 30 分钟」。
     *
     * <p><b>刻意不做成配置项。</b>它是安全基线不是调优参数：做成可配之后，
     * 「谁把它调成了 24 小时」这件事没有任何东西会报错。
     */
    public static final Duration SIGNED_URL_TTL = Duration.ofMinutes(30);

    /** 敏感文件保留期 <b>7 天</b>（{@code 00-通用约定} §7.4 末行、PRD F4-4 规则 3、03-05 §4.8）。 */
    public static final Duration TEMP_FILE_RETENTION = Duration.ofDays(7);

    /** 单文件通用上限 <b>100MB</b>（03-01 §7.1 逐字）。 */
    public static final long MAX_SIZE_DEFAULT = 100L * 1024 * 1024;

    /** 图片上限 <b>10MB</b>（03-01 §7.1 逐字「单文件上限 100MB（图片 10MB）」）。 */
    public static final long MAX_SIZE_IMAGE = 10L * 1024 * 1024;

    /**
     * {@code material_attach} 上限 <b>50MB</b>
     * （03-01 §7.1 逐字「{@code material_attach} 单附件 ≤50MB」，源出 PRD F2-2 规则 2）。
     */
    public static final long MAX_SIZE_MATERIAL_ATTACH = 50L * 1024 * 1024;

    /**
     * {@code import_excel} 上限 <b>5MB</b>
     * （04-实施计划.md 模块 17 规则 10「仅 {@code .xlsx}、单文件 ≤ 5MB」，源出 PRD F1-6 规则 1）。
     */
    public static final long MAX_SIZE_IMPORT_EXCEL = 5L * 1024 * 1024;

    /** 上传频次闸：窗口 60 秒（{@code 00-通用约定} §8 的既有形态，D-5 定案新增一行）。 */
    public static final Duration UPLOAD_RATE_WINDOW = Duration.ofSeconds(60);

    /** 上传频次闸：窗口内单用户上限 20 次（D-5 定案）。 */
    public static final int UPLOAD_RATE_LIMIT = 20;

    /** 频次闸的 Redis 键前缀，形如 {@code rate:file:upload:{userId}}。 */
    public static final String UPLOAD_RATE_KEY_PREFIX = "rate:file:upload:";
}
