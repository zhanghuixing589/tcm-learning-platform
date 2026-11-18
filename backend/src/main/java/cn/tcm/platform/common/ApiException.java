package cn.tcm.platform.common;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status,String code,String message) { super(message); this.status=status; this.code=code; }
    public static ApiException unauthorized() { return new ApiException(401,"UNAUTHENTICATED","登录已失效，请重新登录"); }
    public static ApiException conflict(String message) { return new ApiException(409,"STATE_CONFLICT",message); }
    public static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    public static ApiException missing() { return new ApiException(404,"NOT_FOUND","记录不存在或无权访问"); }
}
