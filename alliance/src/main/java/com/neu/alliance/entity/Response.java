package com.neu.alliance.entity;

public class Response<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> Response<T> success(T data) {
        Response<T> r = new Response<>();
        r.setCode(200);
        r.setMsg("操作成功");
        r.setData(data);
        return r;
    }

    public static <T> Response<T> error(String msg) {
        Response<T> r = new Response<>();
        r.setCode(500);
        r.setMsg(msg);
        return r;
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
