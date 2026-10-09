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
    /**
     * 将用户管理业务异常转换为统一 JSON 错误响应。
     * @param exception 用户不存在或最后一个管理员不能降级的业务异常
     * @return 用户不存在返回 404，降级最后一个管理员返回 409
     */
    @ExceptionHandler(UserManagementException.class)
    public ResponseEntity<ErrorResponse> userManagementFailed(UserManagementException exception) {
        if (exception.reason() == UserManagementException.Reason.USER_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("USER_NOT_FOUND", "用户不存在"));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("LAST_ADMIN", "不能将最后一个启用的管理员降级"));
    }

    /**
     * 将注册业务异常转换为统一 JSON 错误响应。
     * @param exception 注册参数不合法或用户名重复的业务异常
     * @return 用户名重复返回 409，参数不合法返回 400
     */
    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ErrorResponse> registrationFailed(RegistrationException exception) {
        if (exception.reason() == RegistrationException.Reason.USERNAME_TAKEN) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("USERNAME_TAKEN", "用户名已被注册"));
        }
        return invalidRequest();
    }

    /**
     * 统一处理账号不存在、密码错误或账号被禁用等认证失败。
     * 使用相同错误信息，避免通过登录响应推断账号是否存在。
     * @return HTTP 401，错误码为 INVALID_CREDENTIALS
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> authenticationFailed() {
        // 返回统一信息，不泄露账号是否存在或已被禁用。
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("INVALID_CREDENTIALS", "用户名或密码错误"));
    }

    /**
     * 处理参数校验失败、JSON 解析失败及注册参数错误，不回显敏感输入。
     * @return HTTP 400，错误码为 INVALID_REQUEST
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> invalidRequest() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", "请求参数不合法"));
    }
}
