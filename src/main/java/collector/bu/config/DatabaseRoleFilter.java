package collector.bu.config;

import collector.bu.dao.UserDao;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在授权前刷新数据库身份，确保已有会话不能继续使用被撤销的权限。 */
public class DatabaseRoleFilter extends OncePerRequestFilter {
    private final UserDao users;
    private final SecurityContextRepository contexts;

    /**
     * 注入账号查询和会话安全上下文组件，使每次请求都能读取最新权限。
     * @param users 数据库账号查询组件
     * @param contexts 更新会话登录状态的组件
     */
    public DatabaseRoleFilter(UserDao users, SecurityContextRepository contexts) {
        this.users = users;
        this.contexts = contexts;
    }

    /**
     * 在权限检查之前，从数据库重新读取已登录账号的身份和启用状态。
     * 账号不存在、被逻辑删除或禁用时销毁会话；否则创建新的安全上下文保存最新身份，
     * 避免旧会话保留已撤销权限，也避免修改并发请求共享的上下文对象。
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param chain 后续过滤器链，更新状态后继续执行
     * @throws ServletException 后续过滤器处理请求失败
     * @throws IOException 读取请求或写入响应失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            var account = users.findByUsername(authentication.getName());
            if (account.isEmpty() || !account.get().enabled() || account.get().deleted()) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
            } else {
                var principal = User.withUsername(account.get().username()).password("")
                        .roles(account.get().role().name()).build();
                var refreshed = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        principal.getAuthorities());
                refreshed.setDetails(authentication.getDetails());
                // 新建上下文，避免修改同一会话的并发请求共享的实例。
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(refreshed);
                SecurityContextHolder.setContext(context);
                contexts.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
