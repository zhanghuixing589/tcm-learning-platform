package cn.tcm.platform.account.api;

import cn.tcm.platform.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Order(Ordered.HIGHEST_PRECEDENCE)
// Size failures can happen before Spring resolves a controller, so this handler must be global.
@RestControllerAdvice
public class AvatarExceptionHandler {
    @ExceptionHandler({MultipartException.class,MissingServletRequestPartException.class})
    ResponseEntity<?> invalidUpload(Exception error,HttpServletRequest request) {
        String message=request.getRequestURI().equals("/api/v1/me/avatar")
                ?"请选择2MB以内的PNG或JPEG头像":"请选择10MB以内的TXT、DOCX或带文本层的PDF资料";
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR",message));
    }
}
