package collector.bu.controller;

import collector.bu.controller.model.ErrorResponse;
import collector.bu.service.RegistrationException;
import collector.bu.service.UserManagementException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(UserManagementException.class)
    public ResponseEntity<ErrorResponse> userManagementFailed(UserManagementException exception) {
        if (exception.reason() == UserManagementException.Reason.USER_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("USER_NOT_FOUND", "用户不存在"));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("LAST_ADMIN", "不能将最后一个启用的管理员降级"));
    }

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ErrorResponse> registrationFailed(RegistrationException exception) {
        if (exception.reason() == RegistrationException.Reason.USERNAME_TAKEN) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("USERNAME_TAKEN", "用户名已被注册"));
        }
        return invalidRequest();
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> authenticationFailed() {
        // Do not disclose whether an account exists or is disabled.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("INVALID_CREDENTIALS", "用户名或密码错误"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> invalidRequest() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", "请求参数不合法"));
    }
}
