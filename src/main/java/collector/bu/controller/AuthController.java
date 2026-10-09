package collector.bu.controller;

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

    public AuthController(AuthService authService, HttpServletRequest request, HttpServletResponse response,
            SecurityContextRepository contexts, SessionAuthenticationStrategy sessions) {
        this.authService = authService;
        this.request = request;
        this.response = response;
        this.contexts = contexts;
        this.sessions = sessions;
    }

    @Override
    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> getCsrf() {
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ResponseEntity.ok(new CsrfResponse(csrf.getToken(), csrf.getHeaderName(), csrf.getParameterName()));
    }

    @Override
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> register(String csrfToken, RegisterRequest body) {
        authService.register(body.getUsername(), body.getPassword(), body.getConfirmPassword());
        return ResponseEntity.status(201).body(new UserResponse(body.getUsername(), Role.USER));
    }

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

    @Override
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        return ResponseEntity.ok(userResponse(SecurityContextHolder.getContext().getAuthentication()));
    }

    private UserResponse userResponse(org.springframework.security.core.Authentication authentication) {
        var role = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")) ? Role.ADMIN : Role.USER;
        return new UserResponse(authService.currentUsername(authentication), role);
    }

    @Override
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(String csrfToken) {
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }
}
