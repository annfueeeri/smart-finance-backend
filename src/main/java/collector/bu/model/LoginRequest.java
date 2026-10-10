package collector.bu.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 登录请求，校验账号格式和密码长度。 */
public record LoginRequest(
        @NotNull @Pattern(regexp = "\\S+") @Size(min = 1, max = 64) String username,
        @NotNull @Size(min = 1, max = 72) String password) { }
