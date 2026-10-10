package collector.bu.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 注册请求，包含独立姓名、账号、可选联系资料和记账偏好。 */
public record RegisterRequest(
        @NotNull @Pattern(regexp = "\\S+") @Size(min = 1, max = 64) String username,
        @NotNull @Pattern(regexp = "[^\\x00-\\x1F\\x7F]+") @Size(min = 1, max = 80) String displayName,
        @Pattern(regexp = "^$|^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") @Size(max = 254) String email,
        @Pattern(regexp = "^$|^\\+?[0-9][0-9 ()-]{5,29}[0-9]$") @Size(max = 32) String phone,
        @NotNull @Size(min = 8, max = 72) String password,
        @NotNull @Size(min = 8, max = 72) String confirmPassword,
        @Pattern(regexp = "[A-Z]{3}") @Size(min = 3, max = 3) String currency,
        @Size(max = 64) String timezone,
        @Pattern(regexp = "^$|^[0-9]{1,12}(\\.[0-9]{1,4})?$") String monthlyBudget,
        @Min(1) @Max(28) Integer budgetStartDay) { }
