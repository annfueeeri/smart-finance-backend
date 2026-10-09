package collector.bu.controller.auth;

import collector.bu.controller.api.AuthApi;
import collector.bu.controller.model.CsrfResponse;
import collector.bu.controller.model.LoginRequest;
import collector.bu.controller.model.RegisterRequest;
import collector.bu.controller.model.Role;
import collector.bu.controller.model.UserResponse;
import collector.bu.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController implements AuthApi {
    private final AuthService authService;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;

    /**
     * 注入认证业务和当前 HTTP 请求、响应，以及会话和安全上下文管理组件。
     * @param authService 注册和登录业务
     * @param request 当前请求，用于读取 CSRF 和会话
     * @param response 当前响应，用于写入会话 Cookie
     * @param contexts 登录状态的持久化组件
     * @param sessions 登录后的会话安全策略
     */
    public AuthController(AuthService authService, HttpServletRequest request, HttpServletResponse response,
            SecurityContextRepository contexts, SessionAuthenticationStrategy sessions) {
        this.authService = authService;
        this.request = request;
        this.response = response;
        this.contexts = contexts;
        this.sessions = sessions;
    }

    /**
     * 处理 GET /api/auth/csrf，返回当前会话的 CSRF 令牌及其请求头、参数名称。
     * 浏览器需同时保留会话 Cookie；登录成功后应重新获取令牌。
     * @return HTTP 200，响应包含 token、headerName 和 parameterName
     */
    @Override
    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> getCsrf() {
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ResponseEntity.ok(new CsrfResponse(csrf.getToken(), csrf.getHeaderName(), csrf.getParameterName()));
    }

    /**
     * 处理 POST /api/auth/register，校验注册信息并创建一般用户账号。
     * 密码由业务层进行哈希处理，注册成功后仍需单独登录。
     * @param csrfToken CSRF 请求头值，由 Spring Security 在进入方法前校验
     * @param body 独立的姓名、登录账号、可选联系邮箱与手机号、密码和确认密码
     * @return HTTP 201，返回新账号的用户名和 USER 身份
     */
    @Override
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> register(String csrfToken, RegisterRequest body) {
        authService.register(body.getUsername(), body.getPassword(), body.getConfirmPassword(),
                body.getDisplayName(), body.getEmail(), body.getPhone(), body.getCurrency(), body.getTimezone(),
                body.getMonthlyBudget(), body.getBudgetStartDay());
        return ResponseEntity.status(201).body(new UserResponse(body.getUsername(), Role.USER));
    }

    /**
     * 处理 POST /api/auth/login，通过数据库账号和密码哈希完成认证。
     * 成功后轮换会话 ID、清除登录前的 CSRF 令牌，并把登录状态保存到服务端会话。
     * @param csrfToken CSRF 请求头值，由 Spring Security 在进入方法前校验
     * @param body 用户名和明文密码，仅用于此次认证
     * @return HTTP 200，返回用户名和身份；响应 Cookie 用于后续登录态识别
     */
    @Override
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> login(String csrfToken, LoginRequest body) {
        var authentication = authService.login(body.getUsername(), body.getPassword());
        sessions.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.ok(userResponse(authentication));
    }

    /**
     * 处理 GET /api/auth/me，读取已认证会话中的用户名和当前身份。
     * 数据库角色过滤器在此方法之前刷新身份，未登录请求由安全过滤器返回 401。
     * @return HTTP 200，返回当前用户名和身份
     */
    @Override
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        return ResponseEntity.ok(userResponse(SecurityContextHolder.getContext().getAuthentication()));
    }

    /**
     * 将认证结果转换为对外用户信息，根据 ROLE_ADMIN 判断管理员身份。
     * @param authentication 已通过认证的登录信息
     * @return 只包含用户名和身份的响应，不包含密码或密码哈希
     */
    private UserResponse userResponse(org.springframework.security.core.Authentication authentication) {
        var role = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")) ? Role.ADMIN : Role.USER;
        return new UserResponse(authService.currentUsername(authentication), role);
    }

    /**
     * 处理 POST /api/auth/logout，销毁服务端会话并清除安全上下文。
     * 旧会话无法继续访问受保护接口，刷新页面后也必须重新登录。
     * @param csrfToken 当前会话的 CSRF 请求头值，由安全过滤器预先校验
     * @return HTTP 204，没有响应体
     */
    @Override
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(String csrfToken) {
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }
}
