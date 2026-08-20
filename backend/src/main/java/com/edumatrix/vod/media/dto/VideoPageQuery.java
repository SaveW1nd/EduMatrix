package com.edumatrix.vod.media.dto;

/** 接口 26 媒资分页列表（03-03 §7.3）。{@code pageSize} 上限 100 由 {@code PageResult} 强制。 */
public class VideoPageQuery {

    private Integer pageNum;
    private Integer pageSize;
    /** 媒资名称，模糊匹配。 */
    private String videoName;
    /** 状态筛选：0 上传中 1 转码中 2 正常 3 转码失败 9 禁用。 */
    private Integer status;

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getVideoName() {
        return videoName;
    }

    public void setVideoName(String videoName) {
        this.videoName = videoName;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
