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

/** Reload the database role before authorization: old sessions cannot retain revoked privileges. */
public class DatabaseRoleFilter extends OncePerRequestFilter {
    private final UserDao users;
    private final SecurityContextRepository contexts;

    public DatabaseRoleFilter(UserDao users, SecurityContextRepository contexts) {
        this.users = users;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            var account = users.findByUsername(authentication.getName());
            if (account.isEmpty() || !account.get().enabled()) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
            } else {
                var principal = User.withUsername(account.get().username()).password("")
                        .roles(account.get().role().name()).build();
                var refreshed = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        principal.getAuthorities());
                refreshed.setDetails(authentication.getDetails());
                // Do not mutate a SecurityContext instance shared by concurrent session requests.
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(refreshed);
                SecurityContextHolder.setContext(context);
                contexts.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
