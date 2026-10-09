package collector.bu.service;

import org.springframework.security.core.Authentication;

public interface AuthService {
    Authentication login(String username, String password);

    String currentUsername(Authentication authentication);
}
