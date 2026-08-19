package com.edumatrix.system.file.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code sys_file} 文件表（03-01 §7，02-数据库设计 §4.1.7）。
 *
 * <p>覆盖头像、课程封面、图文资料附件、Excel 导入文件、导出报表等通用文件。
 * <b>视频不走这张表</b> —— 视频经 VOD 上传凭证直传云端（03-01 §7 引言逐字，
 * 见 03-03 {@code /api/v1/vod/**}）。
 *
 * <h2>{@code file_url} 存的是<b>对象键</b>，不是可访问地址</h2>
 * <p>DDL 注释写「访问 URL / 存储路径」，两种都允许。本模块<b>一律存对象键</b>
 * （{@code FileKeys} 生成的 {@code {bizType}/{yyyy/MM/dd}/{fileId}.{ext}}），理由是硬的：
 * 存可访问地址就等于把一个长期有效的直链落了库，而
 * {@code 00-通用约定} §7.4 第 1 行逐字「禁止下发长期有效的公开直链」。
 * 地址一律在<b>下发那一刻</b>由 {@code ObjectStorage#presignedUrl} 现签、≤30 分钟。
 *
 * <p>连带影响一处分册措辞：03-03 §0.4 说 {@code coverUrl} 是「根据 {@code cover_file_id}
 * 关联 {@code sys_file.file_url} <b>计算</b>的展示字段」—— 「计算」在这里就是"拿键去现签"，
 * 不是"把这一列读出来直接返回"。D-2 定案已把这层含义写进 {@code 00-通用约定} §7.4。
 *
 * <h2>没有 {@code expire_at} 列</h2>
 * <p>DDL 里确实没有（基线第 231~248 行）。所以 7 天保留期（§7.4 末行）只能按
 * {@code biz_type + create_time} 判 —— {@code TempFileCleanupJob} 就是这么写的。
 * 想加一列要走 Flyway 增量脚本 + 改 02-数据库设计字段表 + 契约 §4 关键字段行，
 * 而按 {@code create_time} 判在语义上完全等价（保留期是固定 7 天，不按文件可配）。
 */
@TableName("sys_file")
public class SysFile extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 原始文件名，{@code VARCHAR(255)}。<b>只用于展示与下载时回填</b>，绝不进对象键。 */
    private String fileName;

    /** 对象键（见类注释），{@code VARCHAR(500)}。 */
    private String fileUrl;

    /** 字节数。 */
    private Long fileSize;

    /** <b>魔数判定出的</b>规范扩展名（如 {@code xlsx}），不是用户声明的那个。 */
    private String fileType;

    /** {@code 1} 本地 {@code 2} OSS，取自 {@code ObjectStorage#storageType()}。 */
    private Integer storage;

    /** 业务类型，取值见 {@code common/file/FileBizType}。 */
    private String bizType;

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Integer getStorage() {
        return storage;
    }

    public void setStorage(Integer storage) {
        this.storage = storage;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }
}
