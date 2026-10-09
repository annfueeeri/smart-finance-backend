package collector.bu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import collector.bu.dao.UserDao;
import collector.bu.service.impl.LocalAccountInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTests {
    private static final String PASSWORD = "Test-only-password-123";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired UserDao userDao;

    /**
     * 在每个用例前清理测试账号，并准备启用账号与禁用账号及其 BCrypt 密码哈希。
     */
    @BeforeEach
    void prepareAccounts() {
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'registration-%'");
        jdbc.update("DELETE FROM app_user WHERE username IN (?, ?)", "login-test", "disabled-test");
        String hash = encoder.encode(PASSWORD);
        jdbc.update("INSERT INTO app_user (username, password_hash, enabled) VALUES (?, ?, ?)",
                "login-test", hash, true);
        jdbc.update("INSERT INTO app_user (username, password_hash, enabled) VALUES (?, ?, ?)",
                "disabled-test", hash, false);
    }

    /**
     * 验证注册只保存密码哈希、不自动登录，且新账号可随后通过密码登录。
     */
    @Test
    void registrationCreatesHashedAccountThatCanLogInWithoutAutomaticallySigningIn() {
        var browser = new Browser();
        browser.refreshCsrf();
        var result = browser.register("registration-new", PASSWORD, PASSWORD);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().get("username").asText()).isEqualTo("registration-new");
        assertThat(result.getBody().size()).isEqualTo(2);
        assertThat(result.getBody().get("role").asText()).isEqualTo("USER");
        var account = userDao.findByUsername("registration-new").orElseThrow();
        assertThat(account.enabled()).isTrue();
        assertThat(account.passwordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, account.passwordHash())).isTrue();
        assertThat(browser.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(browser.login("registration-new", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/api/auth/me").getBody().get("username").asText())
                .isEqualTo("registration-new");
    }

    /**
     * 验证重复注册被拒绝，原账号和禁用账号的数据不会被覆盖。
     */
    @Test
    void duplicateRegistrationDoesNotChangeExistingOrDisabledAccounts() {
        var browser = new Browser();
        browser.refreshCsrf();
        for (String username : List.of("login-test", "disabled-test")) {
            var original = userDao.findByUsername(username).orElseThrow();
            var result = browser.register(username, "Different-password-456", "Different-password-456");
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(result.getBody().get("code").asText()).isEqualTo("USERNAME_TAKEN");
            assertThat(userDao.findByUsername(username).orElseThrow()).isEqualTo(original);
        }
    }

    /**
     * 验证非法注册字段返回 400，并且不会留下新账号。
     */
    @Test
    void registrationRejectsInvalidPayloadsAndNeverCreatesAnAccount() {
        var browser = new Browser();
        browser.refreshCsrf();
        for (var body : List.of(
                Map.of("username", "registration-invalid", "password", PASSWORD),
                Map.of("username", "", "password", PASSWORD, "confirmPassword", PASSWORD),
                Map.of("username", "invalid user", "password", PASSWORD, "confirmPassword", PASSWORD),
                Map.of("username", "x".repeat(65), "password", PASSWORD, "confirmPassword", PASSWORD),
                Map.of("username", "registration-invalid", "password", "short", "confirmPassword", "short"),
                Map.of("username", "registration-invalid", "password", "        ", "confirmPassword", "        "),
                Map.of("username", "registration-invalid", "password", PASSWORD, "confirmPassword", "Mismatch-password"),
                Map.of("username", "registration-invalid", "password", "密".repeat(25), "confirmPassword", "密".repeat(25)),
                Map.of("username", "registration-invalid", "password", "x".repeat(73), "confirmPassword", "x".repeat(73)))) {
            var result = browser.post("/api/auth/register", body);
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(browser.post("/api/auth/register", "{broken").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userDao.findByUsername("registration-invalid")).isEmpty();
    }

    /**
     * 验证注册必须携带与当前会话匹配的 CSRF 令牌。
     */
    @Test
    void registrationRequiresCsrfTokenFromTheSameSession() {
        assertThat(new Browser().register("registration-csrf", PASSWORD, PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var one = new Browser();
        one.refreshCsrf();
        var two = new Browser();
        two.refreshCsrf();
        two.csrf = one.csrf;
        assertThat(two.register("registration-csrf", PASSWORD, PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userDao.findByUsername("registration-csrf")).isEmpty();
    }

    /**
     * 验证达到 72 个 UTF-8 字节边界的有效密码仍可注册和登录。
     */
    @Test
    void registrationAcceptsPasswordsAtTheUtf8ByteLimit() {
        var browser = new Browser();
        browser.refreshCsrf();
        String password = "密".repeat(24);
        assertThat(browser.register("registration-unicode", password, password).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(browser.login("registration-unicode", password).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * 验证同名并发注册只有一个成功，数据库不会产生重复账号。
     */
    @Test
    void simultaneousRegistrationCreatesExactlyOneAccount() throws Exception {
        var one = new Browser();
        one.refreshCsrf();
        var two = new Browser();
        two.refreshCsrf();
        var first = CompletableFuture.supplyAsync(() -> one.register("registration-race", PASSWORD, PASSWORD));
        var second = CompletableFuture.supplyAsync(() -> two.register("registration-race", PASSWORD, PASSWORD));
        assertThat(List.of(first.get(15, TimeUnit.SECONDS).getStatusCode(),
                second.get(15, TimeUnit.SECONDS).getStatusCode()))
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class,
                "registration-race")).isEqualTo(1);
    }

    /**
     * 验证登录轮换会话 ID，登录状态只对保留对应 Cookie 的浏览器有效。
     */
    @Test
    void loginRotatesSessionAndAuthenticatesOnlyThatBrowser() {
        var browser = new Browser();
        browser.refreshCsrf();
        String originalCookie = browser.cookie;
        var result = browser.login("login-test", PASSWORD);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().get("username").asText()).isEqualTo("login-test");
        assertThat(result.getBody().size()).isEqualTo(2);
        assertThat(result.getBody().get("role").asText()).isEqualTo("USER");
        assertThat(result.getHeaders().get(HttpHeaders.SET_COOKIE).toString())
                .contains("HttpOnly", "SameSite=Strict");
        assertThat(browser.cookie).isNotEqualTo(originalCookie);
        assertThat(browser.get("/api/auth/me").getBody().get("username").asText())
                .isEqualTo("login-test");
        assertThat(new Browser().get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var oldSession = new Browser();
        oldSession.cookie = originalCookie;
        assertThat(oldSession.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 验证错误密码、不存在的账号和禁用账号返回相同认证错误。
     */
    @Test
    void badPasswordUnknownAccountAndDisabledAccountHaveTheSameResponse() {
        var wrong = new Browser();
        wrong.refreshCsrf();
        var badPassword = wrong.login("login-test", "incorrect");
        var unknown = new Browser();
        unknown.refreshCsrf();
        var missingAccount = unknown.login("missing-test", PASSWORD);
        var disabled = new Browser();
        disabled.refreshCsrf();
        var disabledAccount = disabled.login("disabled-test", PASSWORD);
        for (var result : List.of(badPassword, missingAccount, disabledAccount)) {
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(result.getBody()).isEqualTo(badPassword.getBody());
            assertThat(result.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        }
        assertThat(wrong.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 验证登录请求必须通过同会话 CSRF 校验。
     */
    @Test
    void loginRequiresCsrfTokenFromTheSameSession() {
        assertThat(new Browser().login("login-test", PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var one = new Browser();
        one.refreshCsrf();
        var two = new Browser();
        two.refreshCsrf();
        two.csrf = one.csrf;
        assertThat(two.login("login-test", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * 验证缺失或不合法的登录字段返回 400。
     */
    @Test
    void invalidCredentialsPayloadReturnsBadRequest() {
        var browser = new Browser();
        browser.refreshCsrf();
        for (var body : List.of(Map.of("username", "login-test"),
                Map.of("username", "", "password", PASSWORD),
                Map.of("username", "   ", "password", PASSWORD),
                Map.of("username", "x".repeat(65), "password", PASSWORD),
                Map.of("username", "login-test", "password", "x".repeat(73)))) {
            var result = browser.post("/api/auth/login", body);
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(browser.post("/api/auth/login", "{broken").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * 验证超出字节限制的密码及 SQL 注入形式的用户名都不能通过认证。
     */
    @Test
    void oversizedUtf8PasswordAndSqlInjectionCannotAuthenticate() {
        var browser = new Browser();
        browser.refreshCsrf();
        assertThat(browser.login("login-test", "密".repeat(25)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(browser.login("'/**/OR/**/1=1--", PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 验证本地初始化只保存哈希，重复初始化不覆盖已有账号凭据。
     */
    @Test
    void localBootstrapStoresOnlyHashAndNeverOverwritesExistingAccounts() {
        String username = "bootstrap-test";
        jdbc.update("DELETE FROM app_user WHERE username = ?", username);
        try {
            new LocalAccountInitializer(userDao, encoder, username, PASSWORD).run(null);
            var account = userDao.findByUsername(username).orElseThrow();
            assertThat(account.passwordHash()).isNotEqualTo(PASSWORD);
            assertThat(encoder.matches(PASSWORD, account.passwordHash())).isTrue();
            new LocalAccountInitializer(userDao, encoder, username, "Different-password-456").run(null);
            assertThat(userDao.findByUsername(username).orElseThrow().passwordHash())
                    .isEqualTo(account.passwordHash());
        } finally {
            jdbc.update("DELETE FROM app_user WHERE username = ?", username);
        }
    }

    /**
     * 验证本地初始化拒绝不完整或超出长度限制的凭据。
     */
    @Test
    void localBootstrapRejectsIncompleteAndOversizedCredentials() {
        for (var initializer : List.of(
                new LocalAccountInitializer(userDao, encoder, "", PASSWORD),
                new LocalAccountInitializer(userDao, encoder, "bootstrap-test", ""),
                new LocalAccountInitializer(userDao, encoder, "bootstrap-test", "short"),
                new LocalAccountInitializer(userDao, encoder, "bootstrap-test", "密".repeat(25)))) {
            assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 验证退出要求登录后的有效 CSRF 令牌，退出后受保护接口不能再用原会话访问。
     */
    @Test
    void logoutRequiresFreshCsrfAndInvalidatesTheSession() {
        var browser = new Browser();
        browser.refreshCsrf();
        assertThat(browser.login("login-test", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Login clears the pre-login token as well as rotating the session id.
        assertThat(browser.post("/api/auth/logout", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        browser.refreshCsrf();
        assertThat(browser.post("/api/auth/logout", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(browser.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 验证匿名请求只能访问健康检查、接口文档等公开路径。
     */
    @Test
    void onlyPublicRoutesAreAvailableWithoutAuthentication() {
        var browser = new Browser();
        assertThat(browser.get("/api/health").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/v3/api-docs").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/api/private").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private class Browser {
        private String cookie;
        private String csrf;
        private String csrfHeader = "X-CSRF-TOKEN";

        /**
         * 获取当前测试浏览器会话的 CSRF 令牌及请求头名称，供后续写请求使用。
         */
        void refreshCsrf() {
            var result = get("/api/auth/csrf");
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            csrf = result.getBody().get("token").asText();
            csrfHeader = result.getBody().get("headerName").asText();
        }

        /**
         * 用指定用户名和密码向登录接口发送 JSON 请求，返回原始响应供用例断言。
         */
        ResponseEntity<JsonNode> login(String username, String password) {
            return post("/api/auth/login", Map.of("username", username, "password", password));
        }

        /**
         * 将用户名、密码和确认密码发送到注册接口，返回原始响应供用例断言。
         */
        ResponseEntity<JsonNode> register(String username, String password, String confirmPassword) {
            return post("/api/auth/register", Map.of("username", username,
                    "password", password, "confirmPassword", confirmPassword));
        }

        /**
         * 对给定路径发起无请求体的 GET 请求，沿用当前测试浏览器会话。
         */
        ResponseEntity<JsonNode> get(String path) {
            return exchange(path, HttpMethod.GET, null);
        }

        /**
         * 对给定路径提交 POST 请求体，沿用当前会话和可用的 CSRF 令牌。
         */
        ResponseEntity<JsonNode> post(String path, Object body) {
            return exchange(path, HttpMethod.POST, body);
        }

        /**
         * 模拟独立浏览器发送 HTTP 请求，携带自己的 Cookie 和写请求 CSRF 令牌。
         * 从响应更新 JSESSIONID，便于验证登录、退出和会话隔离。
         */
        private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
            if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
            if (csrf != null && method == HttpMethod.POST) headers.set(csrfHeader, csrf);
            var result = rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
            var cookies = result.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (cookies != null) {
                cookies.stream().filter(value -> value.startsWith("JSESSIONID="))
                        .forEach(value -> cookie = value.split(";", 2)[0]);
            }
            return result;
        }
    }
}
