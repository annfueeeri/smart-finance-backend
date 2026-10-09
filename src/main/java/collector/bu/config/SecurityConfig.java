package collector.bu.config;

import java.util.List;
import collector.bu.dao.UserDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
public class SecurityConfig {
    /**
     * 创建 BCrypt 密码处理组件，注册时生成哈希，登录时校验明文与哈希是否匹配。
     * @return 供认证和账号创建共用的密码编码器
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 创建基于数据库账号的认证管理器，组合账号加载器和密码校验器。
     * 认证提供者负责检查密码、账号启用状态等条件。
     * @param users 从数据库加载账号信息的服务
     * @param encoder 密码哈希校验组件
     * @return 执行用户名密码认证的管理器
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /**
     * 创建以 HTTP Session 保存登录状态的仓库，供后续请求恢复认证信息。
     * @return 服务端会话安全上下文仓库
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 创建绑定 HTTP Session 的 CSRF 令牌仓库，阻止使用其他会话令牌提交写请求。
     * @return 会话级 CSRF 令牌仓库
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    /**
     * 组合登录后的会话安全策略：轮换会话 ID，并清除登录前的 CSRF 令牌。
     * @param csrf 清除和重新生成 CSRF 令牌使用的仓库
     * @return 登录成功时执行的组合策略
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    /**
     * 配置接口访问规则、会话登录状态、CSRF 防护以及统一的 401/403 JSON 响应。
     * 健康检查、获取令牌、注册、登录和接口文档允许匿名访问；管理员路径仅 ADMIN 可用。
     * 其余路径要求登录，并在授权前通过数据库刷新身份；认证由自定义 Controller 处理。
     * @param http Spring Security 的 HTTP 安全配置构建器
     * @param contexts 会话登录状态仓库
     * @param csrf 会话 CSRF 令牌仓库
     * @param sessions 登录成功后的会话安全策略
     * @param users 刷新数据库角色使用的账号查询组件
     * @return 应用使用的安全过滤器链
     * @throws Exception 安全配置构建失败
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository csrf, SessionAuthenticationStrategy sessions, UserDao users) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/login", "/api/auth/register",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new DatabaseRoleFilter(users, contexts), AuthorizationFilter.class)
                .securityContext(context -> context.securityContextRepository(contexts))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionAuthenticationStrategy(sessions))
                .csrf(config -> config.csrfTokenRepository(csrf))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"请先登录\"}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"请求被拒绝，请检查权限及 CSRF token\"}");
                        }))
                .build();
    }
}
