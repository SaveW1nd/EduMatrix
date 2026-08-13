package com.edumatrix.common.response;

import com.edumatrix.common.errorcode.ErrorCode;

/**
 * 统一响应体 {@code {code, msg, data}}（契约 §6.1 / 00-通用约定 §3）。
 *
 * <p><b>全部 Controller 一律返回它</b>。自己拼一个形状不同的响应体，前端的统一拆包逻辑
 * （两个前端工程各有一份 {@code utils/request.ts}）就对不上 —— 而那两份是必须保持一致的
 * （05-工程结构.md §A3 末表）。
 *
 * <p><b>业务错误统一 HTTP 200</b>（00-通用约定 §3.3）：框架层错误（400/401/403/404/405/429/500）
 * 的 HTTP 状态码与 {@code code} 一致；业务错误（1xxxx~4xxxx）HTTP 一律 200，由 {@code code} 判定。
 * 这条不是风格偏好 —— 契约 §2.8 记着一次真实教训：阿里云 HTTP 回调「忽略响应包体、仅以
 * HTTP 状态码为准」，按本约定返回 {@code {"code":20017}} 会被判成投递成功、事件永久丢失。
 *
 * @param <T> data 的类型
 */
public class R<T> {

    private int code;
    private String msg;
    private T data;

    public R() {
    }

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    // ------------------------------------------------------------------ 成功

    public static <T> R<T> ok() {
        return new R<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return new R<>(ErrorCode.SUCCESS.getCode(), msg, data);
    }

    // ------------------------------------------------------------------ 失败

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMsg(), null);
    }

    /**
     * 覆写提示语。用于 msg 需要携带动态信息的场景 —— 例如 {@code 10005} 要在 msg 里
     * 提示账号锁定的剩余时间（00-通用约定 §8）。<b>code 仍必须来自枚举</b>。
     */
    public static <T> R<T> fail(ErrorCode errorCode, String msg) {
        return new R<>(errorCode.getCode(), msg, null);
    }

    /**
     * 携带业务数据的失败。用于「整批拒绝并告诉调用方是哪几条越界」的接口 ——
     * 例如 {@code 30011} 要在 {@code data.invalidStudentIds} 里列出越界学员，
     * {@code 10107} 要让用户知道该重新选哪个目标。
     */
    public static <T> R<T> fail(ErrorCode errorCode, T data) {
        return new R<>(errorCode.getCode(), errorCode.getMsg(), data);
    }

    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
