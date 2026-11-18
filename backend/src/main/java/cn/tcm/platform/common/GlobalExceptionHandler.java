package cn.tcm.platform.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(ApiResponse.error(e.code,e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class,ConstraintViolationException.class,
            HttpMessageNotReadableException.class,HandlerMethodValidationException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> validation(Exception e) { return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR","请求内容不符合要求，请检查字段和长度")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(Exception e) { return ResponseEntity.status(409).body(ApiResponse.error("STATE_CONFLICT","记录已存在或状态冲突")); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> forbidden(Exception e) { return ResponseEntity.status(403).body(ApiResponse.error("FORBIDDEN","没有执行此操作的权限")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        LOG.error("Unhandled request error type={}",e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR","服务暂时不可用，请稍后重试"));
    }
}
